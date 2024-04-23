$version: "2.0"

namespace example

use smithy.rules#endpointRuleSet
use smithy.rules#endpointTests

@endpointRuleSet({
    parameters: {
        Region: { type: "string", builtIn: "AWS::Region", required: true, documentation: "docs" }
    }
    rules: [
        {
            documentation: "base rule"
            conditions: [
                {
                    fn: "aws.partition"
                    argv: [
                        {
                            ref: "Region"
                        }
                    ]
                    assign: "PartResult"
                }
            ]
            rules: [
                {
                    documentation: "the AWS partition"
                    conditions: [
                        {
                            fn: "stringEquals"
                            argv: [
                                "aws"
                                {
                                    fn: "getAttr"
                                    argv: [
                                        {
                                            ref: "PartResult"
                                        }
                                        "name"
                                    ]
                                }
                            ]
                        }
                    ]
                    endpoint: {
                        url: "https://aws-partition.{Region}.{PartResult#dnsSuffix}"
                        properties: {
                            authSchemes: [
                                {
                                    name: "sigv4"
                                    signingName: "serviceName"
                                    signingRegion: "{Region}"
                                }
                            ]
                            meta: { baseSuffix: "{PartResult#dnsSuffix}", dualStackSuffix: "{PartResult#dualStackDnsSuffix}" }
                        }
                    }
                    type: "endpoint"
                }
                {
                    documentation: "the other partitions"
                    conditions: []
                    endpoint: {
                        url: "https://{PartResult#name}.{Region}.{PartResult#dnsSuffix}"
                        properties: {
                            authSchemes: [
                                {
                                    name: "sigv4"
                                    signingName: "serviceName"
                                    signingRegion: "{Region}"
                                }
                            ]
                            meta: { baseSuffix: "{PartResult#dnsSuffix}", dualStackSuffix: "{PartResult#dualStackDnsSuffix}" }
                        }
                    }
                    type: "endpoint"
                }
                {
                    conditions: []
                    error: "no rules matched"
                    type: "error"
                }
            ]
            type: "tree"
        }
    ]
    version: "1.3"
})
@endpointTests(
    version: "1.0"
    testCases: [
        {
            documentation: "standard AWS region"
            params: { Region: "us-east-2" }
            expect: {
                endpoint: {
                    url: "https://aws-partition.us-east-2.amazonaws.com"
                    properties: {
                        authSchemes: [
                            {
                                name: "sigv4"
                                signingName: "serviceName"
                                signingRegion: "us-east-2"
                            }
                        ]
                        meta: { baseSuffix: "amazonaws.com", dualStackSuffix: "api.aws" }
                    }
                }
            }
        }
        {
            documentation: "AWS region that doesn't match any regexes"
            params: { Region: "mars-global" }
            expect: {
                endpoint: {
                    url: "https://aws-partition.mars-global.amazonaws.com"
                    properties: {
                        authSchemes: [
                            {
                                name: "sigv4"
                                signingName: "serviceName"
                                signingRegion: "mars-global"
                            }
                        ]
                        meta: { baseSuffix: "amazonaws.com", dualStackSuffix: "api.aws" }
                    }
                }
            }
        }
        {
            documentation: "AWS region that matches the AWS regex"
            params: { Region: "us-east-10" }
            expect: {
                endpoint: {
                    url: "https://aws-partition.us-east-10.amazonaws.com"
                    properties: {
                        authSchemes: [
                            {
                                name: "sigv4"
                                signingName: "serviceName"
                                signingRegion: "us-east-10"
                            }
                        ]
                        meta: { baseSuffix: "amazonaws.com", dualStackSuffix: "api.aws" }
                    }
                }
            }
        }
        {
            documentation: "CN region that matches the AWS regex"
            params: { Region: "cn-north-5" }
            expect: {
                endpoint: {
                    url: "https://aws-cn.cn-north-5.amazonaws.com.cn"
                    properties: {
                        authSchemes: [
                            {
                                name: "sigv4"
                                signingName: "serviceName"
                                signingRegion: "cn-north-5"
                            }
                        ]
                        meta: { baseSuffix: "amazonaws.com.cn", dualStackSuffix: "api.amazonwebservices.com.cn" }
                    }
                }
            }
        }
        {
            documentation: "CN region that is in the explicit list"
            params: { Region: "aws-cn-global" }
            expect: {
                endpoint: {
                    url: "https://aws-cn.aws-cn-global.amazonaws.com.cn"
                    properties: {
                        authSchemes: [
                            {
                                name: "sigv4"
                                signingName: "serviceName"
                                signingRegion: "aws-cn-global"
                            }
                        ]
                        meta: { baseSuffix: "amazonaws.com.cn", dualStackSuffix: "api.amazonwebservices.com.cn" }
                    }
                }
            }
        }
    ]
)
service FizzBuzz {}
