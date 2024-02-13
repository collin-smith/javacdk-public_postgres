package com.myorg;

import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
// import software.amazon.awscdk.Duration;
// import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.ec2.DefaultInstanceTenancy;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpoint;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.IpAddresses;
import software.amazon.awscdk.services.ec2.NatProvider;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.IInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;

public class JavacdkPublicPostgresStack extends Stack {
    public JavacdkPublicPostgresStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public JavacdkPublicPostgresStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Environment variable to separate the environments
        String application = "RDS";
        String environment = "dev";
        String vpcName = application+"-"+environment+"VPC";
        String databaseName = application+""+environment+"DB";
        String secretName = application+""+environment+"dbsecret";
        
        SubnetConfiguration s1 = SubnetConfiguration.builder()
        .cidrMask(24)
        .name(vpcName+"-"+environment+"Public")
        .subnetType(SubnetType.PUBLIC)
        .build();
          
        SubnetConfiguration s2 = SubnetConfiguration.builder()
        .cidrMask(24)
        .name(vpcName+"-"+environment+"PrivateIsolated")
        .subnetType(SubnetType.PRIVATE_ISOLATED)
        .build();
    
        ArrayList<SubnetConfiguration> subnets = new ArrayList<SubnetConfiguration>();
        subnets.add(s1);
        subnets.add(s2);

        
        //Availability zones for the VPC in this region
        List<String> azs = new ArrayList<String>();
        azs.add(this.getRegion()+"a");
        azs.add(this.getRegion()+"b");
        azs.add(this.getRegion()+"c");

        //Can use https://cidr.xyz/ to better design internet addresses
        
        Vpc vpc = Vpc.Builder.create(this, vpcName+"-"+environment)
        .ipAddresses(IpAddresses.cidr("10.0.0.0/20"))
        .defaultInstanceTenancy(DefaultInstanceTenancy.DEFAULT)
        .enableDnsSupport(true)
        .enableDnsHostnames(true)
        .availabilityZones(azs)
        .subnetConfiguration(subnets)
        //.natGateways(1)
        //.natGatewayProvider(NatProvider.gateway())
        .build();
                  
        SecurityGroup lambdaSecurityGroup = SecurityGroup.Builder.create(this, "lambdaSecurityGroup")
      	.vpc(vpc)
      	.description("Security Group for the Spring Boot Lambda")
      	.securityGroupName("lambdaSecurityGroup")
      	.build(); 
        lambdaSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.allTraffic(), "Allow incoming Lambda traffic");

        String username="RDSDEV_username";
	        		  
        // This will generate a JSON object with the keys "username" and "password".
        //Note that the other values in the secret will be added during the database instance creation(engine,port,dbInstanceIdentifier, host)
        //the alternative to this would be to do the following in the DatabaseInstance creation (.credentials(Credentials.fromGeneratedSecret(secretName)))
        Secret databaseSecret = Secret.Builder.create(this, "databaseSecret")
        .secretName(secretName)
        .description("Credentials to the RDS instance")
        .generateSecretString(SecretStringGenerator.builder()
        .secretStringTemplate(String.format("{\"username\": \"%s\"}", username))
        .generateStringKey("password")
        .passwordLength(32)
        .excludeCharacters("@/\\\" ")
        .build())
        .build();

        //Database security Group
        SecurityGroup databaseSecurityGroup = SecurityGroup.Builder.create(this, "databaseSecurityGroup")
      	.vpc(vpc)
      	.description("Security Group for the database instance")
      	.securityGroupName("dbSecurityGroup")
      	.build();     
        //Allow lambda access from the lambda Security Group
        databaseSecurityGroup.addIngressRule(lambdaSecurityGroup, Port.tcp(5432), "PostgreSQL From the lambda Security group");
        //Allow public access to this database 
        databaseSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(5432), "PostgresSQL from anywhere");

        ArrayList<SecurityGroup> sgs = new ArrayList<SecurityGroup>();
        sgs.add(databaseSecurityGroup);
        
        final IInstanceEngine instanceEngine = DatabaseInstanceEngine.postgres(
        PostgresInstanceEngineProps.builder()
        .version(PostgresEngineVersion.VER_16_1)
        .build());
        
        final DatabaseInstance databaseInstance = DatabaseInstance.Builder.create(this, databaseName)
      	.vpc(vpc)
      	.vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
      	.instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
      	.allocatedStorage(20)
      	.engine(instanceEngine)
      	.instanceIdentifier(id + "-rds")
      	//This line works well if you don't actually need to reference the Secret itself (for VPCEndpoint)
      	//.credentials(Credentials.fromGeneratedSecret(secretName))
      	//Option to reference a preexisting SecretManager value
      	.credentials(Credentials.fromSecret(databaseSecret))
      	.securityGroups(sgs)
      	//Add removal policy if you want to not do the final snapshot before deletion
      	//(Do not have RemovalPolicy.DESTROY set for production environments)
      	.removalPolicy(RemovalPolicy.DESTROY) 
      	.build();
           
        //Spring Boot Considerations: 
        //You are required to have a VPC Endpoint to access Secrets Manager within a Custom VPC
        //You can access Secrets Manager without a VPC Endpoint if you deploy your Spring Boot application to the default VPC
        //https://serverlessland.com/patterns/lambda-secretsmanager-dotnet-cdk

        InterfaceVpcEndpoint smVpcEndpoint = InterfaceVpcEndpoint.Builder.create(this, "SecretsManager VPC Endpoint")
        .vpc(vpc)
        .privateDnsEnabled(true)
        .securityGroups(sgs)
        .service(InterfaceVpcEndpointAwsService.SECRETS_MANAGER)
        .subnets(SubnetSelection.builder()
        .availabilityZones(azs)
        .build())
        .build();
                
        //Display the results of the CDK for validation and future stack configuration
        
        CfnOutput.Builder.create(this, "ZA Region")
        .description("")
         .value("Region:"+ this.getRegion())
         .build();
        
        CfnOutput.Builder.create(this, "ZB VPC Created:")
        .description("")
         .value("VPC Id:"+ vpc.getVpcId())
         .build();
         
        String vpcAzString = "VPC Availability Zones: ";
        List<String> vpcAzs = vpc.getAvailabilityZones();
        for (int i=0;i<vpcAzs.size();i++)
        {
        	String vpcAzItem = vpcAzs.get(i);
        	vpcAzString += " ("+vpcAzItem+") ";
        }

        CfnOutput.Builder.create(this, "ZC VPC Availability Zones:")
        .description("")
         .value("VPC Availability Zones:"+ vpcAzString)
         .build();
        
        String subnetDescription = "Public Subnets: ";
        List<ISubnet> publicSubnets = vpc.getPublicSubnets();
        for (int i=0;i<publicSubnets.size();i++)
        {
        	ISubnet publicSubnet = publicSubnets.get(i);
        	subnetDescription += " ("+publicSubnet.getSubnetId()+") ";
        }
        CfnOutput.Builder.create(this, "ZD VPC Public subnets Created:")
        .description("")
         .value("# Public subnets:"+ vpc.getPublicSubnets().size()+":"+subnetDescription)
         .build();
            
        subnetDescription = "Private Isolated Subnets: ";
        List<ISubnet> isolatedSubnets = vpc.getIsolatedSubnets();
        for (int i=0;i<isolatedSubnets.size();i++)
        {
        	ISubnet isolatedSubnet = isolatedSubnets.get(i);
        	subnetDescription += " ("+isolatedSubnet.getSubnetId()+") ";
        }

        CfnOutput.Builder.create(this, "ZE VPC Private Isolated subnets Created:")
        .description("")
         .value("# Private Isolated subnets:"+ vpc.getIsolatedSubnets().size()+":"+subnetDescription)
         .build();
        
        CfnOutput.Builder.create(this, "ZF RDS Endpoint:")
        .description("")
         .value("RDS Endpoint:"+ databaseInstance.getDbInstanceEndpointAddress()+":"+databaseInstance.getDbInstanceEndpointPort())
         .build();

        CfnOutput.Builder.create(this, "ZG Lambda Security Group:")
        .description("")
         .value("Lambda Security Group Id:"+ lambdaSecurityGroup.getSecurityGroupId())
         .build();
        
        CfnOutput.Builder.create(this, "ZH Database Secret ARN:")
        .description("")
         .value("Database Secret arn:"+ databaseSecret.getSecretArn())
         .build();

        CfnOutput.Builder.create(this, "ZI Secrets Manager VPC Endpoint:")
        .description("")
         .value("Secrets Manager VPC Endpoint:"+ smVpcEndpoint.getVpcEndpointId())
         .build();
        
        
    }
}
