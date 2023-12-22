package chongwm.demo.aws.community.examples;

import com.amazonaws.services.s3.AmazonS3URI;

public class S3V1Utils
{
	//String bucketName;
	//String keyPath;
	private AmazonS3URI aS3Uri;
	
	public S3V1Utils(String s3Uri)
	{
		aS3Uri = new AmazonS3URI(s3Uri);
		//this.bucketName = s3Uri.substring(5, s3Uri.indexOf("/", 5));		
		//this.keyPath = s3Uri.substring(s3Uri.indexOf(bucketName)+bucketName.length()+1, s3Uri.length());	
	}
	
	public String getBucketName()
	{
		return aS3Uri.getBucket();
	}
	
	public String getKeyPath()
	{
		return aS3Uri.getKey();
	}
	
	public AmazonS3URI getURI()
	{
		return aS3Uri;
	}
	
}