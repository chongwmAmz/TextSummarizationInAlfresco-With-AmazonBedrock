package chongwm.demo.amazon.aws.bedrock.summarization;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;

import com.amazonaws.SdkClientException;
import com.amazonaws.arn.Arn;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.bedrock.model.GetModelInvocationJobResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.AmazonS3URI;
import com.amazonaws.services.s3.model.GetObjectTaggingRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectTagging;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.SetObjectTaggingRequest;
import com.amazonaws.services.s3.model.Tag;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import chongwm.demo.amazon.aws.bedrock.BedrockAsyncInference;
import chongwm.demo.aws.community.examples.S3V1Utils;
import chongwm.demo.hyland.alfresco.search.pojo.json2kt.Properties;

public class BatchedSummarizerAlfrescoUpdateHandler implements RequestHandler<Map<String, Object>, Integer>
{

	protected static String awsSessionToken = System.getenv("AWS_SESSION_TOKEN");
	protected static String awsSecretsExtensionHTTPPort = System.getenv("PARAMETERS_SECRETS_EXTENSION_HTTP_PORT");
	private static String BedrockInvokedTagKey = System.getenv("BedrockInvokedTagKey");
	private static String InputS3Uri = System.getenv("InputS3Uri");
	private static String OutputS3Uri = System.getenv("OutputS3Uri");
	protected static String url = System.getenv("alfrescoHost"); // acs.sgpeks.duckdns.org
	protected static String userId = System.getenv("alfrescoSA"); // ignored if Lambda environment variable awsSecretsManagerSecretArn is populated
	protected static String password = System.getenv("alfrescoPass"); // ignored if Lambda environment variable awsSecretsManagerSecretArn is populated
	protected static Arn awsSecretsManagerSecretArn = (System.getenv("awsSecretsManagerSecretArn") == null) ? null : Arn.fromString(System.getenv("awsSecretsManagerSecretArn"));
	protected static boolean httpProtocol = ("https".compareToIgnoreCase(System.getenv("alfrescoHostProtocol")) == 0) ? true : false;
	protected static SimpleDateFormat alfrescoDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
	protected static Random randomizer = new Random(System.currentTimeMillis());
	protected static AmazonS3 s3Client = AmazonS3ClientBuilder.standard().build();
	protected static int OutputRandomizerPrefixLength;
	protected static DefaultAWSCredentialsProviderChain awsCredentialProviderChain = DefaultAWSCredentialsProviderChain.getInstance();
	protected static S3V1Utils inputS3Utils;
	protected static S3V1Utils outputS3Utils;
	private LambdaLogger logger;
	private boolean localDebug = false;
	protected String encodedTicket;
	protected CloseableHttpClient httpClient = null;
	private String ticket;
	protected static BedrockAsyncInference bAI = new BedrockAsyncInference();

	record JobRecord(String completion, JsonObject error)
	{
		JobRecord(String completion, JsonObject error)
		{
			this.completion = completion;
			this.error = error;
		}
	}

	@Override
	public Integer handleRequest(Map<String, Object> input, Context context)
	{
		if (!localDebug)
			this.logger = context.getLogger();
		String logStr = "Lambda function triggered with alfrescoHostProtocol=" + httpProtocol + " alfrescoHost=" + url + " s3InputBucket=" + inputS3Utils.getBucketName() + ". s3InPath=" + inputS3Utils.getKeyPath()+ " s3TransientOutputBucket=" + outputS3Utils.getBucketName() + ". s3TOutPath=" + outputS3Utils.getKeyPath();
		if (this.awsSecretsManagerSecretArn == null)
			logStr = logStr + " alfrescoSA=" + userId;
		else
			logStr = logStr + " awsSecretsArn=" + this.awsSecretsManagerSecretArn.toString();
		logOrPrint(logStr);
		
		logOrPrint("Retrieving JSONL input files from s3InputBucketPath for Bedrock batch processing.");
		logOrPrint(SendToBatchBedrock()+ " JSONL files sent for Bedrock batch processing.");
		
		
		int attemptCount = 0;
		try
		{
			getAwsSecretAndAlfrescoTicket();
			
			List<AmazonS3URI> objects = GetS3ObjectsInPath(outputS3Utils.getBucketName(), outputS3Utils.getKeyPath());
			logOrPrint("Processing completed batches (likely from last run).");
			for (AmazonS3URI object : objects)
			{
				String objectKeyName = object.getKey();
				if (objectKeyName.endsWith("jsonl.out") && s3Client.getObjectMetadata(object.getBucket(), object.getKey()).getContentLength()>0)
				{
					String jobContents = s3Client.getObjectAsString(object.getBucket(), objectKeyName);
					JobRecord record = GetJobProduct(jobContents);
					try
					{
						if (UpdateAlfrescoNodeWithSummary(GetAlfrescoNodeIdFromS3Key(objectKeyName), record.completion, s3Client.getObjectMetadata(object.getBucket(), objectKeyName).getLastModified()) // yes, update the Alfresco node from the error thrown by Bedrock.
						    && record.error != null)
						{
							s3Client.deleteObject(object.getBucket(), objectKeyName);
						}
						attemptCount ++;
					} catch (IOException e)
					{
						logOrPrint(e.getMessage() + ". When updating " + GetAlfrescoNodeIdFromS3Key(objectKeyName));
						// TODO decide if the for loop below to delete S3 files should skip this one that failed Alfresco update.
					}
				}
				this.httpClient.close();
				this.ticket = getAlfrescoTicket(); // Since each HttpClient only provides 2 routes, a new one will have to be created.
			}
			for (AmazonS3URI object : objects)
			{// delete files like the manifest files that aren't needed anymore
				try
				{
					if (s3Client.getObjectMetadata(object.getBucket(), object.getKey()).getContentLength()>0)
						s3Client.deleteObject(object.getBucket(), object.getKey()); //avoid deleting 0 byte objects as they are placeholders made by the currently running Bedrock batch inference jobs.
				} catch (SdkClientException e)
				{
					System.out.println(e.getMessage() + ". Likely because the object has already been deleted.");
				}
			}
		}
		catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | IOException | InterruptedException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return attemptCount;
	}



	protected GetModelInvocationJobResult CreateBedockAsyncBatch(AmazonS3URI jsonlFile, AmazonS3URI outputS3Path)
	{
		String s3Path = jsonlFile.getKey();
		String jobName = s3Path.substring(s3Path.lastIndexOf("/")+1, s3Path.lastIndexOf("/")+1+OutputRandomizerPrefixLength);
		//logOrPrint("Specifying outPath as "+outputS3Path.getURI().toString()+" to BatchBedrock."); //TODO remove debug
		Arn jobArn = Arn.fromString(bAI.createModelInvokeJobSampleCode(awsCredentialProviderChain, jsonlFile, outputS3Path, jobName));
		GetModelInvocationJobResult result = bAI.getModelInvokeJobSampleCode(jobArn);
		return result;
	}

	protected String getTagValue(List<Tag> tags, String tagKey)
	{
		for (Tag t : tags)
		{
			if (tagKey.equalsIgnoreCase(t.getKey()))
				return t.getValue();
		}
		return null;
	}
	
	protected boolean tagMatch(List<Tag> tags, String tagKey, String tagValue)
	{
		String value = getTagValue(tags, tagKey);
		if (value != null)
			if (tagValue.equalsIgnoreCase(value))
				return true;
		return false;
	}
	
	protected List<Tag> setTagValue(List<Tag> tags, String tagKey, String tagValue) 
	{
		boolean newValueAdded=false;
		List<Tag> newTags = new ArrayList<Tag>();
		for (Tag t : tags)
		{
			if (tagKey.equalsIgnoreCase(t.getKey()))
			{
				newTags.add(new Tag(t.getKey(), tagValue));
				newValueAdded = true;
			}
			else
				newTags.add(new Tag(t.getKey(), t.getValue()));
		}
		if (!newValueAdded)
			newTags.add(new Tag(tagKey, tagValue));		
		return newTags;
	}
	
	
	protected int SendToBatchBedrock()
	{
		List<AmazonS3URI> jsonlObjs = GetS3ObjectsInPath(inputS3Utils.getBucketName(), inputS3Utils.getKeyPath());
		int doneCount = 0;
		for ( AmazonS3URI oneJsonl: jsonlObjs)
		{
			if (oneJsonl.getKey().charAt(oneJsonl.getKey().length()-1)!='/')
			{//assumes that any object that ends with a / is a directory and should be skipped
				List<Tag> tagSet = s3Client.getObjectTagging(new GetObjectTaggingRequest(oneJsonl.getBucket(), oneJsonl.getKey())).getTagSet();
				if (!tagMatch(tagSet, BedrockInvokedTagKey, "Invoked") )
				{
					GetModelInvocationJobResult oneBatch = CreateBedockAsyncBatch(oneJsonl, new AmazonS3URI(outputS3Utils.getURI().toString()));
					s3Client.setObjectTagging(new SetObjectTaggingRequest(oneJsonl.getBucket(), oneJsonl.getKey(), 
							                  new ObjectTagging(setTagValue(tagSet, BedrockInvokedTagKey, "Invoked"))));  // so it doesn't get called again. We'll deal with cleanup later
				}				
				//s3Client.deleteObject(oneJsonl.getBucket(), oneJsonl.getKey()); // cleanup the input file
				doneCount ++;
			}
		}
		return doneCount;
	}
	
	
	private void getAwsSecretAndAlfrescoTicket() throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, IOException, InterruptedException
	{
		if ((this.awsSecretsManagerSecretArn != null))
		{
			getSecretValueFromLambdaLayer(this.awsSecretsManagerSecretArn.toString()); // this function populates userId and password
		}
		// else this.userId and this.password would be populated statically when the this class is created before lambda handler is called.

		this.ticket = this.getAlfrescoTicket();
		this.encodedTicket = Base64.getEncoder().encodeToString(this.ticket.getBytes());
	}

	/****
	 * This method trusts all certificates in order to accommodate websites that use self-signed certificates
	 * 
	 * @return Alfresco ticket
	 * @throws KeyStoreException,
	 *             NoSuchAlgorithmException, KeyManagementException, IOException
	 */
	protected String getAlfrescoTicket() throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, IOException
	{
		String idVal = null;
		SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, (chain, authType) -> true).build(); // SSL context that trusts all certificates

		// Use the above SSL context to create a HTTP client that accepts self-signed certificates.
		// this.httpClient = HttpClients.custom().setSSLContext(sslContext).setSSLHostnameVerifier((hostname, session) -> true).build();
		this.httpClient = HttpClients.custom().setDefaultRequestConfig(RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build()).setSSLContext(sslContext).setSSLHostnameVerifier((hostname, session) -> true).build();

		String httpTicketUrl = "://" + this.url + "/alfresco/api/-default-/public/authentication/versions/1/tickets";
		httpTicketUrl = this.httpProtocol ? "https" + httpTicketUrl : "http" + httpTicketUrl;
		HttpPost request = new HttpPost(httpTicketUrl);
		String json = "{\"userId\":\"" + this.userId + "\",\"password\":\"" + this.password + "\"}";
		StringEntity entity = new StringEntity(json, StandardCharsets.UTF_8);
		entity.setContentType("application/json");
		request.setEntity(entity);
		CloseableHttpResponse httpResponse = this.httpClient.execute(request);
		HttpEntity httpEntity = httpResponse.getEntity();
		org.json.JSONObject jsonResponse = new org.json.JSONObject(EntityUtils.toString(httpEntity));
		idVal = jsonResponse.getJSONObject("entry").getString("id");
		httpResponse.close();
		return idVal;
	}

	public static String getSecretValueFromLambdaLayer(String secretToGet)
	{
		// System.out.println("Token length is " + System.getenv("AWS_SESSION_TOKEN").length() + " Port is " + System.getenv("PARAMETERS_SECRETS_EXTENSION_HTTP_PORT"));
		HttpClient httpClient = HttpClients.createDefault();

		// Create an HTTP GET request with the specified endpoint and request header
		HttpGet httpGet = new HttpGet("http://localhost:" + awsSecretsExtensionHTTPPort + "/secretsmanager/get?secretId=" + secretToGet);
		httpGet.setHeader("X-Aws-Parameters-Secrets-Token", awsSessionToken);
		// System.out.println("Endpoint is " + httpGet.toString()+" X-Aws-Parameters-Secrets-Token is " + awsSessionToken);

		// Send the HTTP GET request
		HttpResponse response = null;
		// Process the response (e.g., parse the JSON response)
		try
		{
			response = httpClient.execute(httpGet);
			String responseBody = EntityUtils.toString(response.getEntity());
			if (responseBody.startsWith("{"))
			{
				// System.out.println("JSON response Body: " + responseBody);
				Gson gson = new Gson();
				JsonObject secretStringJson = gson.fromJson(gson.fromJson(responseBody, JsonObject.class).get("SecretString").getAsString(), JsonObject.class);

				for (String key : secretStringJson.keySet())
				{
					password = secretStringJson.get(key).getAsString();
					userId = key;
				}
			}
			else
				System.out.println("No JSON, response Body: " + responseBody);
		} catch (Exception e)
		{
			System.out.println("Error processing response: " + e.getMessage());
		} finally
		{
			httpClient = null;
		}
		return password;
	}
	public BatchedSummarizerAlfrescoUpdateHandler()
	{
		inputS3Utils = new S3V1Utils(InputS3Uri);
		outputS3Utils = new S3V1Utils(OutputS3Uri);
		if (System.getenv("OutputRandomizerPrefixLength") == null)
			OutputRandomizerPrefixLength = 12; // pass in 12
		else
			OutputRandomizerPrefixLength = Integer.parseInt(System.getenv("OutputRandomizerPrefixLength"));
	}

	protected List<AmazonS3URI> GetS3ObjectsInPath(String bucketName, String path)
	{
		List<AmazonS3URI> aU = new ArrayList<AmazonS3URI>();
		try
		{

			// List objects in the specified path
			ListObjectsV2Result result = s3Client.listObjectsV2(bucketName, path);
			List<S3ObjectSummary> objects = result.getObjectSummaries();

			for (S3ObjectSummary objectSummary : objects)
			{
				// String debug = objectSummary.getBucketName()+"/"+objectSummary.getKey();
				aU.add(new AmazonS3URI("s3://" + objectSummary.getBucketName() + "/" + objectSummary.getKey()));
			}
		} catch (Exception e)
		{
			// Handle any exceptions that occur during the listing process
			e.printStackTrace();
		} finally
		{
			// Close the S3 client to release resources
			// s3Client.close();
		}
		return aU;
	}

	protected JobRecord GetJobProduct(String json)
	{
		JsonObject jobj = new Gson().fromJson(json, JsonObject.class);
		JsonObject modelOutput = jobj.getAsJsonObject("modelOutput");
		JsonObject error = null;
		String completionString;
		if (modelOutput == null)
		{
			error = jobj.getAsJsonObject("error");
			completionString = jobj.get("recordId").getAsString() + " completed with code " + error.get("errorCode").getAsString() + ":" + error.get("errorMessage").getAsString();
		}
		else
			completionString = modelOutput.get("completion").getAsString();
		return (new JobRecord(completionString, error));
	}

	public static String removeFirstLine(String input, boolean emptyLinesAfterFirstLine)
	{
		// Remove the first line (up to the first newline character)
		int indexOfFirstNewline = input.indexOf('\n');
		String removedTopLine = input.substring(indexOfFirstNewline + 1);
		if (emptyLinesAfterFirstLine && removedTopLine.startsWith("\n"))
			removedTopLine = removeFirstLine(removedTopLine, emptyLinesAfterFirstLine);
		return removedTopLine;
	}

	public static String removeFirstSentence(String input)
	{
		input = input.trim();
		int indexOfFirstSentence = input.indexOf(". ");
		String removedFirstSentence = input.substring(indexOfFirstSentence + 1);
		return removedFirstSentence.trim();
	}
	
	private boolean UpdateAlfrescoNodeWithSummary(String nodeId, String genAiSummary, Date summaryDate) throws HttpResponseException, IOException
	{
		Properties p = new Properties();
		//p.setCrestBedrock_summary(removeFirstLine(genAiSummary, true)); // Claude usually titles its responses, let's remove the first line.
		p.setCrestBedrock_summary(removeFirstSentence(genAiSummary)); // Claude usually titles its responses, let's remove the first sentence.
		p.setCrestBedrock_generateSummary(false);
		p.setCrestBedrock_summaryTime(summaryDate);
		return updateAlfrescoNode(p, nodeId);
	}

	protected boolean updateAlfrescoNode(Properties prop, String nodeId) throws HttpResponseException, IOException
	{
		String alfrescoNodeUpdateRestEndpoint = "://" + this.url + "/alfresco/api/-default-/public/alfresco/versions/1/nodes/" + nodeId;
		alfrescoNodeUpdateRestEndpoint = this.httpProtocol ? "https" + alfrescoNodeUpdateRestEndpoint : "http" + alfrescoNodeUpdateRestEndpoint;

		JsonObject propBody = new JsonObject();
		propBody.addProperty("crestBedrock:summary", prop.getCrestBedrock_summary());
		propBody.addProperty("crestBedrock:generateSummary", prop.getCrestBedrock_generateSummary());
		propBody.addProperty("crestBedrock:summaryTime", alfrescoDateFormat.format(prop.getCrestBedrock_summaryTime()));
		JsonObject jsonBody = new JsonObject();
		jsonBody.add("properties", propBody);

		String returnMsg = performPutRestApiCall(alfrescoNodeUpdateRestEndpoint, jsonBody);
		if (!(returnMsg == null))
		{
			logOrPrint(returnMsg + " returned by Alfresco repository when attempting to update node " + nodeId);
			return false;
		}
		else return true;
	}
	protected void logOrPrint(String str)
	{
		if (localDebug)
			System.out.println(str);
		else
			this.logger.log(str+"\n");
	}

	protected String performPutRestApiCall(String endpoint, JsonObject jsonObject) throws HttpResponseException, IOException, JsonSyntaxException
	{
		HttpPut httpPut = new HttpPut(endpoint);

		httpPut.setHeader("Accept", "application/json");
		httpPut.setHeader("Content-Type", "application/json");
		httpPut.setHeader("Authorization", "Basic " + this.encodedTicket);
		httpPut.setEntity(new StringEntity(jsonObject.toString()));

		HttpResponse response = this.httpClient.execute(httpPut);
		String returnMsg = null; // if null, update is successful
		if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK)
		{
			switch (response.getStatusLine().getStatusCode())
			{
				case HttpURLConnection.HTTP_BAD_REQUEST :
					returnMsg = "The update request is invalid or nodeId is not a valid format or nodeBodyUpdate is invalid.";
					break;
				case HttpURLConnection.HTTP_UNAUTHORIZED :
					returnMsg = "Authentication failed.";
					break;
				case HttpURLConnection.HTTP_FORBIDDEN :
					returnMsg = "Current user does not have permission to update nodeId.";
					break;
				case HttpURLConnection.HTTP_NOT_FOUND :
					returnMsg = "nodeId does not exist.";
					break;
				default :
					returnMsg = "Unexpected error.";
			}
		}
		return returnMsg;
	}

	private String GetAlfrescoNodeIdFromS3Key(String key)
	{
		// /BedrockTempFolder/batch/input/JQrQsZTtvZhG0e0d70ca-6442-4afa-ac9c-f05badb863bc.jsonl
		String nodeId = key.substring(key.lastIndexOf("/") + 1);
		nodeId = nodeId.substring(OutputRandomizerPrefixLength, nodeId.lastIndexOf(".jsonl"));
		return nodeId;
	}

	public static void main(String[] args)
	{
		BatchedSummarizerAlfrescoUpdateHandler bsauh = new BatchedSummarizerAlfrescoUpdateHandler();
		bsauh.localDebug = true;
		bsauh.handleRequest(null, null);
		/*
		AmazonS3URI as3 = new AmazonS3URI(bsauh.OutputS3Uri);
		System.out.println(as3.getURI().toString());
		System.out.println(as3.toString());
		System.out.println(bsauh.OutputS3Uri);
		bsauh.CreateBedockAsyncBatch(new AmazonS3URI(bsauh.InputS3Uri + "jsonLFile.jsonl"), as3);
		*/
		
		//System.out.println("Return code is " + bsauh.handleRequest(null, null));
		//bsauh.handleRequest(null, null);

		/*
		 * AWSCredentials credentials = null; try { credentials = new ProfileCredentialsProvider().getCredentials(); } catch (Exception e) { throw new AmazonClientException( "Cannot load the credentials from the credential profiles file. " +
		 * "Please make sure that your credentials file is at the correct " + "location (~/.aws/credentials), and is in valid format.", e); } credentials.
		 * 
		 * AmazonS3 s3Client = AmazonS3ClientBuilder.standard().build();
		 */

		/*
		 * BedrockAsyncInference bAI = new BedrockAsyncInference(); DefaultAWSCredentialsProviderChain awsCredentialProviderChain = DefaultAWSCredentialsProviderChain.getInstance();
		 * 
		 * for (int a=1;a<args.length;a++) { //"s3://kendra-alf/BedrockTempFolder/batch/input/HUSSERgEczCd|61d90367-d8e4-490f-a52d-7d6f8a64ea76" //"s3://bedrock-batch-inference-virginia-alfresco/BedrockTempFolder/batch/input/JQrQsZTtvZhG0e0d70ca-6442-4afa-ac9c-f05badb863bc.jsonl"
		 * AmazonS3URI s3uIn = new AmazonS3URI(args[a]); AmazonS3URI s3uOut = new AmazonS3URI(args[0]); String jobName = args[a].substring(args[a].lastIndexOf("/")+1, args[a].lastIndexOf("/")+1+12)+"-"+a; Arn jobArn =
		 * Arn.fromString(bAI.createModelInvokeJobSampleCode(awsCredentialProviderChain, s3uIn, s3uOut, jobName)); GetModelInvocationJobResult result = bAI.getModelInvokeJobSampleCode(jobArn); System.out.println(result.getStatus()); while
		 * (result==null||"Submitted".equalsIgnoreCase(result.getStatus())||"InProgress".equalsIgnoreCase(result.getStatus())) { try { Thread.sleep(5000); //result =
		 * bAI.getModelInvokeJobSampleCode(Arn.fromString("arn:aws:bedrock:us-east-1:817632472177:model-invocation-job/kgtrwvc4vt5y")); result = bAI.getModelInvokeJobSampleCode(jobArn); System.out.print("|"+result.getStatus()); } catch (InterruptedException e) { // TODO
		 * Auto-generated catch block e.printStackTrace(); } } long endTime; if (result.getEndTime()==null) endTime=System.currentTimeMillis(); else endTime=result.getEndTime().getTime(); System.out.println("\n"+result.getStatus()+"|"+result.getMessage());
		 * System.out.println("Inference took "+((endTime-result.getSubmitTime().getTime())/1000)+"seconds."); } // TODO:Sleep. Find a way to get access token. probably the same as S3.
		 */

	}

}
