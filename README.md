# Summarizing documents with Amazon Bedrock

## Introduction

![Logical Flow Diagram](./Resources/LogicalFlowDiagram.png)

[Amazon Bedrock](https://aws.amazon.com/bedrock/) is a fully managed service that offers a choice of high-performing foundation models (FMs) along with a broad set of capabilities that you need to build generative AI applications, simplifying development with security, privacy and responsible AI. Amazon Bedrock leverages AWS Lambda for invoking actions, Amazon S3 for training and validation data, and Amazon CloudWatch for tracking metrics.

With Amazon Bedrock, you can get a summary of textual content such as articles, blog posts, books, and documents to get the gist without having to read the full content. Other use cases that Amazon Bedrock can quickly get you started on are listed in the [Amazon Bedrock FAQ](https://aws.amazon.com/bedrock/faqs/).

## Overview

In this post, we describe the use of Alfresco Content Services ReST and Amazon Bedrock SDK APIs to provide a summary of a document’s textual content from an Alfresco repository. We assume that you are competent at developing software using Alfresco Content Services ReST and AWS Java SDK APIs.

We first add the Amazon-Bedrock-Gen-Ai aspect to the repository and apply it documents we want to get summarizations for. Next the AmazonBedrockSummarizationAlfresco lambda function is triggered. This function queries the repository for documents marked for summarization and extracts textual content from those documents. The prompt to Amazon Bedrock is read from the crestBedrock:generateSummary property and concatenated with the textual content and sent to Amazon Bedrock. If the length of the textual content is less than ArbitrarySynchronousBedrockInvocationLength, Amazon Bedrock’s synchronous API is used; else, Amazon Bedrock’s batch API is used. Additionally, if the length of the textual content is greater than BedrockBatchClaudePromptMaxLength, the textual content will be truncated to fit Amazon Bedrock’s allowable prompt length.

For batched Amazon Bedrock requests, a JSONL file containing the textual content is staged on S3.

A separate lambda function, AmazonBatchedBedrockSummarizationAlfresco retrieves JSONL files from S3 and processes them with Amazon Bedrock’s batch API. Amazon Bedrock writes completed batches back into S3 and the same lambda function retrieves them from S3 and writes the Amazon Bedrock generated summarizations into the crestBedock:summary attributes for the respective document nodes in the Alfresco repository.

## Detailed Design

### Aspect

#### [crestBedock:GenAI](https://github.com/chongwmAmz/TextSummarizationInAlfresco-With-AmazonBedrock/blob/main/Resources/Alfresco/Crest-GenAI.zip)

This aspect is applied onto document nodes where summarization is to be done. The description for each property in this aspect can be found within the [aspect](https://github.com/chongwmAmz/TextSummarizationInAlfresco-With-AmazonBedrock/blob/main/Resources/Alfresco/Crest-GenAI.xml).

### AWS Lambda functions (Java 17)

#### AmazonBedrockSummarizationAlfresco

[Amazon Bedrock SDK v2.22.x](https://mvnrepository.com/artifact/software.amazon.awssdk/bedrockruntime) is used for this function.

|   |   |   |  
|---|---|---|
|Environment variable name|Purpose|Default (Example) value|
|PARAMETERS_SECRETS\_<br>EXTENSION_HTTP_PORT|Refer [AWS SM in AWS Lambda](https://docs.aws.amazon.com/secretsmanager/latest/userguide/retrieving-secrets_lambda.html)|2773|
|**alfrescoHost**|Name of host for Alfresco repository|None (acme.alfrescocloud.com)|
|**alfrescoHostProtocol**|Connection protocol for Alfresco repository|https (https\|http)|
|**awsSecrets<br>ManagerSecretArn**|ARN of secret that contains the Alfresco repository credentials to query, retrieve and update document nodes|None<br><br>(arn:aws:secretsmanager:  <br>us-east-1:XXXXXXXXXX:  <br>secret:YYYY)|
|**s3Uri**|Location to stage textual content for asynchronous inference|None  <br>(s3://bbbbbbbb/fffff/)|
|**queryJson**|AFTS query used to locate documents in Alfresco repository marked for summarization|{<br><br>  "query": {<br><br>               "language": "afts",<br><br>               "query":  <br>"TYPE:'cm:content'  <br>AND ASPECT:  <br>'crestBedrock:GenAI'  <br>AND crestBedrock:  <br>generateSummary:'true'  <br>AND name:\*"<br><br>               },<br><br>               "include": [<br><br>                 "properties"<br><br>               ]<br><br>}|
|BedrockRegion|AWS region to run Bedrock inference. See the Bedrock runtime [service endpoint page](https://docs.aws.amazon.com/general/latest/gr/bedrock.html#bedrock_region) for availability.|us-east-1  <br>(us-west-2)||
|**ArbitarySynchronous  <br>BedrockInvocation  <br>Length**|To mitigate a HTTP client timeout issue, prompts with length text are inferred asynchronously|None  <br>(250000)|
|**BedrockBatchClaude<br>PromptMaxLength**|Maximum length of textual content (without include prompt). Any characters exceeding this length are truncated.|None<br><br>(599700)|
|**OutputRandomizer<br>PrefixLength**|Length of string to generated to be Amazon Batch Job Id|None  <br>(12)|
Note: Bolded **variables** are mandatory.

This function queries the repository for the documents ready to be used for Amazon Bedrock inference.

To fine-tune the query, modify the queryJson environment variable for your purpose.

The Alfresco repository credentials to use are kept in an AWS Secrets Manager secret. The secret is a key value pair as follows:

"SecretString": "{\"_userId_\":\"_password_\"}"

For each document returned in the search, the textual content is extracted. Currently only [MIMETYPE_TEXT_PLAIN](https://github.com/Alfresco/alfresco-data-model/blob/5baba42306646cb5cfb11186d632b98fd5996298/src/main/java/org/alfresco/repo/content/MimetypeMap.java#L98C32-L98C52) and [MIMETYPE_PDF](https://github.com/Alfresco/alfresco-data-model/blob/5baba42306646cb5cfb11186d632b98fd5996298/src/main/java/org/alfresco/repo/content/MimetypeMap.java#L114) is supported. For [MIMETYPE_PDF](https://github.com/Alfresco/alfresco-data-model/blob/5baba42306646cb5cfb11186d632b98fd5996298/src/main/java/org/alfresco/repo/content/MimetypeMap.java#L114) content, [Apache PDFBox](https://pdfbox.apache.org/) is used to [extract](https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.0/org/apache/pdfbox/text/PDFTextStripper.html) textual content. The user’s [prompt](https://docs.aws.amazon.com/bedrock/latest/userguide/what-is-a-prompt.html) to the foundational model provided by Amazon Bedrock is read from the property [crestBedrock:fm](https://github.com/chongwmAmz/TextSummarizationInAlfresco-With-AmazonBedrock/blob/e715a7058be6556e9ac990181ed80d5504c56381/Resources/Alfresco/Crest-GenAI.xml#L18) and appended to the end of the textual content.

If the textual content is shorter than ArbitrarySynchronousBedrockInvocationLength, the [Amazon Bedrock runtime synchronous API](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/bedrockruntime/BedrockRuntimeClient.html#invokeModel(software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest)) is used to generate the summary. If the length of the textual content is longer than BedrockBatchClaudePromptMaxLength it will be truncated before it is [packaged](https://docs.aws.amazon.com/bedrock/latest/userguide/batch-inference-data.html) and staged at s3Uri for asynchronous batch processing. Textual content longer than ArbitarySynchronousBedrockInvocationLength but shorter than BedrockBatchClaudePromptMaxLength is [packaged](https://docs.aws.amazon.com/bedrock/latest/userguide/batch-inference-data.html) and staged at the same s3Uri without truncation.

After the request is processed for both synchronous and batched invocation of Amazon Bedrock, the [crestBedrock:generateSummary](https://github.com/chongwmAmz/TextSummarizationInAlfresco-With-AmazonBedrock/blob/e715a7058be6556e9ac990181ed80d5504c56381/Resources/Alfresco/Crest-GenAI.xml#L39) property is reset to false for the respective document node. For synchronous invocations, the generated summary is written to the [crestBedrock:generateSummary](https://github.com/chongwmAmz/TextSummarizationInAlfresco-With-AmazonBedrock/blob/e715a7058be6556e9ac990181ed80d5504c56381/Resources/Alfresco/Crest-GenAI.xml#L39) property; for batched invocations, the batch’s [Amazon Bedrock batch job ID](https://docs.aws.amazon.com/bedrock/latest/userguide/batch-inference-get.html) is written.

#### AmazonBedrockBatchedSummarizationAlfresco

Amazon Bedrock batch inference SDK that is currently in preview; see this [page](https://docs.aws.amazon.com/bedrock/latest/userguide/batch-inference.html) for detail on getting the SDK.


|**Environment variable name**| **Purpose** |**Default (Example) value**|
|------|---|------|
|PARAMETERS_SECRETS\_<br>EXTENSION_HTTP_PORT|Refer [AWS SM in AWS Lambda](https://docs.aws.amazon.com/secretsmanager/latest/userguide/retrieving-secrets_lambda.html)| None <br>(2773)|
|**alfrescoHost**|Name of host for Alfresco repository|None (acme.alfrescocloud.com)|
|**alfrescoHost  <br>Protocol**|Connection protocol for Alfresco repository|https (https\|http)|
|**awsSecretsManager<br>SecretArn**|ARN of secret that contains the Alfresco repository credentials to query, retrieve and update document nodes|None<br><br>(arn:aws:secretsmanager:  <br>us-east-1:XXXXXXXXXX:  <br>secret:YYYY)|
|**InputS3Uri**|Location of batch JSONL files to use for inference|None  <br>(s3://bbb/ffff/batch/input/)|
|**OutputS3Uri**|Location of completed inference results|None  <br>(s3://bbb/ffff/batch/output/)|
|**BedrockInvokedTagKey**|To mark that an input file has been sent for inference|None  <br>(BedockInvoked)|
|**OutputRandomizer<br>PrefixLength**|Length of Amazon Batch Job Id string created by AmazonBedrock<br>SummarizationAlfresco | None <br>(12)|
Note: Bolded **variables** are mandatory.

Textual content from documents that are too long for synchronous Amazon Bedrock invocations are [batched for asynchronous invocation](https://docs.aws.amazon.com/bedrock/latest/userguide/batch-inference.html).

This function lists the JSONL objects (\*.jsonl) in the InputS3Uri path and creates an [Amazon Bedrock batch inference job](https://docs.aws.amazon.com/bedrock/latest/userguide/batch-inference-create.html) for those that are not tagged with the BedrockInvokedTagKey.

Next, it lists the \*.jsonl.out objects in the OutputS3Uri path and extracts the result of the inference. A sample of the Amazon Bedrock batch job output is such:

\{
        "modelInput": {\<repeat of input provided> },
        "modelOutput": {
               "completion": "\<AI generated text>",
               "stop_reason": "stop_sequence",
               "stop": "\n\nHuman:"
        },
        "recordId": "<\AmazonBedrockBatchJobId>"
}

The string contained in modelOutput.completion is used to update the Alfresco document node’s crestBedock:summary attribute. The Alfresco node is identified in the name of the \*.jsonl.out as the substring after OutputRandomizerPrefixLength and the .jsonl.out extension.


### Displaying generated document summary

The business user UI checks the value of the crestBedrock:summary attribute. For each document that has a non-empty crestBedrock:summary, the UI provides a visual indication.

## General guidelines for prompting Amazon Bedrock

See this [page](https://docs.aws.amazon.com/bedrock/latest/userguide/general-guidelines-for-bedrock-users.html). This project places the user’s instruction read from crestBedrock:prompt after the text extracted from the document when prompting Amazon Bedrock.

## Conclusion

This post walks through the salient points in the design to enable document summarization on Alfresco Content Services with Amazon Bedrock.

The sample code, software libraries, command line tools, proofs of concept, templates, scripts, or other related technology (including any of the foregoing that are provided) is provided “as is” without warranty, representation, or guarantee of any kind. All that is provided in the post is without obligation of the author or anyone to provide any support, update, enhance, or guarantee its functionality, quality, or performance. You use the technology in this post at your own risk. Neither the author nor anyone else except you is liable or responsible for any issues arising from errors, omissions, inaccuracies or your use of this post. You are solely responsible for reviewing, testing, validating and determining the suitability of this post for your own purposes. By utilizing this post, you release the author and anyone else from any liability related to your use or implementation of it. You should not use this content in your production accounts, or on production or other critical data.

You are responsible for testing, securing, and optimizing this content, such as sample code and/or template, as appropriate for production-grade use based on your specific quality control practices and standards. Deploying this content may incur AWS charges for creating or using AWS chargeable resources, such as running inference on Amazon Bedrock or programs in AWS Lambda.