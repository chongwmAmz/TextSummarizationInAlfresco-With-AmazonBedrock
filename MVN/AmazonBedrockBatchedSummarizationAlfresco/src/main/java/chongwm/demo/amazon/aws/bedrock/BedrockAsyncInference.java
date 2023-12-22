package chongwm.demo.amazon.aws.bedrock;

import java.util.Date;
import java.util.List;

import com.amazonaws.arn.Arn;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.bedrock.AmazonBedrockAsync;
import com.amazonaws.services.bedrock.AmazonBedrockAsyncClientBuilder;
import com.amazonaws.services.bedrock.model.CreateModelInvocationJobRequest;
import com.amazonaws.services.bedrock.model.CreateModelInvocationJobResult;
import com.amazonaws.services.bedrock.model.GetModelInvocationJobRequest;
import com.amazonaws.services.bedrock.model.GetModelInvocationJobResult;
import com.amazonaws.services.bedrock.model.InvocationJobInputDataConfig;
import com.amazonaws.services.bedrock.model.InvocationJobOutputDataConfig;
import com.amazonaws.services.bedrock.model.InvocationJobS3InputDataConfig;
import com.amazonaws.services.bedrock.model.InvocationJobS3OutputDataConfig;
import com.amazonaws.services.bedrock.model.InvocationJobStatus;
import com.amazonaws.services.bedrock.model.InvocationJobSummary;
import com.amazonaws.services.bedrock.model.ListModelInvocationJobsRequest;
import com.amazonaws.services.bedrock.model.ListModelInvocationJobsResult;
import com.amazonaws.services.bedrock.model.StopModelInvocationJobRequest;
import com.amazonaws.services.bedrock.model.StopModelInvocationJobResult;
import com.amazonaws.services.s3.AmazonS3URI;

public class BedrockAsyncInference
{
	private final AmazonBedrockAsync amazonBedrockAsyncClient = AmazonBedrockAsyncClientBuilder.defaultClient();
	public AmazonBedrockAsync getAmazonBedrockAsyncClient()
	{
		return amazonBedrockAsyncClient;
	}
	public String createModelInvokeJobSampleCode(AWSCredentialsProvider credsProvider,   AmazonS3URI s3UriIn, AmazonS3URI s3UriOut, String jobName)
	{   //s3UriIn = "s3://Input-bucket-name/input/abc.jsonl"
		// jobName = "unique-job-name"
		// s3UriOut = "s3://output-bucket-name/output/"

		final InvocationJobS3InputDataConfig invocationJobS3InputDataConfig = new InvocationJobS3InputDataConfig().withS3Uri(s3UriIn.getURI().toString()).withS3InputFormat("JSONL");

		final InvocationJobInputDataConfig inputDataConfig = new InvocationJobInputDataConfig().withS3InputDataConfig(invocationJobS3InputDataConfig);

		final InvocationJobS3OutputDataConfig invocationJobS3OutputDataConfig = new InvocationJobS3OutputDataConfig().withS3Uri(s3UriOut.getURI().toString());

		final InvocationJobOutputDataConfig invocationJobOutputDataConfig = new InvocationJobOutputDataConfig().withS3OutputDataConfig(invocationJobS3OutputDataConfig);

		final CreateModelInvocationJobRequest createModelInvocationJobRequest = new CreateModelInvocationJobRequest()
																					.withModelId("anthropic.claude-v2:1")																					
				                                                                    .withJobName(jobName)
				                                                                    .withInputDataConfig(inputDataConfig)
				                                                                    .withOutputDataConfig(invocationJobOutputDataConfig)
				                                                                    .withRoleArn("arn:aws:iam::817632472177:role/BedrockBatchRole")
				                                                                    .withRequestCredentialsProvider(credsProvider);

		
		final CreateModelInvocationJobResult createModelInvocationJobResult = amazonBedrockAsyncClient.createModelInvocationJob(createModelInvocationJobRequest);

		return(createModelInvocationJobResult.getJobArn());
	}

	public GetModelInvocationJobResult getModelInvokeJobSampleCode(Arn jobArn)
	{
		final GetModelInvocationJobRequest getModelInvocationJobRequest = new GetModelInvocationJobRequest().withJobIdentifier(jobArn.toString());

		final GetModelInvocationJobResult getModelInvocationJobResult = amazonBedrockAsyncClient.getModelInvocationJob(getModelInvocationJobRequest);
		return getModelInvocationJobResult;

	}

	public ListModelInvocationJobsResult listModelInvokeJobSampleCode(String jobNameToMatch)
	{// jobNameToMatch = "matchin-string"

		final ListModelInvocationJobsRequest listModelInvocationJobsRequest = new ListModelInvocationJobsRequest().withMaxResults(100).withNameContains(jobNameToMatch);//.withStatusEquals(InvocationJobStatus.InProgress);

		final ListModelInvocationJobsResult listModelInvocationJobsResult = amazonBedrockAsyncClient.listModelInvocationJobs(listModelInvocationJobsRequest);
		return listModelInvocationJobsResult;
	}

	public StopModelInvocationJobResult stopModelInvokeJobSampleCode(Arn jobArn)
	{
		final StopModelInvocationJobRequest stopModelInvocationJobRequest = new StopModelInvocationJobRequest().withJobIdentifier(jobArn.toString());

		final StopModelInvocationJobResult stopModelInvocationJobResult = amazonBedrockAsyncClient.stopModelInvocationJob(stopModelInvocationJobRequest);
		return stopModelInvocationJobResult;

	}
	
	public static void main(String args[]) throws InterruptedException  //static method  
	{  
		BedrockAsyncInference bAI = new BedrockAsyncInference();
		while (true)
		{
			ListModelInvocationJobsResult listResults = bAI.listModelInvokeJobSampleCode("SkzrnTUELiQT");
			List<InvocationJobSummary> ll = listResults.getInvocationJobSummaries();
			for (InvocationJobSummary l : ll)
			{
				System.out.print(l.getJobName()+ " has "+l.getStatus()+". "+l.getMessage()+" "  + l.getSubmitTime());
				GetModelInvocationJobResult sr = bAI.getModelInvokeJobSampleCode(Arn.fromString(l.getJobArn()));
				System.out.print(" Job "+sr.getMessage()+" S|"+sr.getStatus());
				System.out.println(". "+"IF is "+InvocationJobStatus.Failed.name()+" IC is "+InvocationJobStatus.Completed.name());
				if (sr.getStatus()==InvocationJobStatus.Failed.name() || sr.getStatus()==InvocationJobStatus.Completed.name())
					break;
				else
					Thread.sleep(5000);
			}		
		}
		
	}

}