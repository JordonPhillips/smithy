$version: "2.0"

namespace example

use smithy.rules#clientContextParams
use smithy.rules#endpointRuleSet
use smithy.rules#endpointTests

@endpointRuleSet({
    version: "1.3"
    parameters: {
        BucketName: { type: "string", required: true, documentation: "the input used to test isVirtualHostableS3Bucket" }
    }
    rules: [
        {
            conditions: [
                {
                    fn: "aws.isVirtualHostableS3Bucket"
                    argv: ["{BucketName}", false]
                }
            ]
            endpoint: { url: "https://{BucketName}.s3.amazonaws.com" }
            type: "endpoint"
        }
        {
            conditions: [
                {
                    fn: "aws.isVirtualHostableS3Bucket"
                    argv: ["{BucketName}", true]
                }
            ]
            endpoint: { url: "http://{BucketName}.s3.amazonaws.com" }
            type: "endpoint"
        }
        {
            conditions: []
            error: "not isVirtualHostableS3Bucket"
            type: "error"
        }
    ]
})
@endpointTests(
    version: "1.0"
    testCases: [
        {
            documentation: "bucket-name:  isVirtualHostable"
            params: { BucketName: "bucket-name" }
            expect: {
                endpoint: { url: "https://bucket-name.s3.amazonaws.com" }
            }
        }
        {
            documentation: "bucket-with-number-1: isVirtualHostable"
            params: { BucketName: "bucket-with-number-1" }
            expect: {
                endpoint: { url: "https://bucket-with-number-1.s3.amazonaws.com" }
            }
        }
        {
            documentation: "bucket--with-multiple-dash: isVirtualHostable"
            params: { BucketName: "bucket--with-multiple-dash" }
            expect: {
                endpoint: { url: "https://bucket--with-multiple-dash.s3.amazonaws.com" }
            }
        }
        {
            documentation: "BucketName: not isVirtualHostable (uppercase characters)"
            params: { BucketName: "BucketName" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "bucket_name: not isVirtualHostable (underscore)"
            params: { BucketName: "bucket_name" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "bucket.name: isVirtualHostable (http only)"
            params: { BucketName: "bucket.name" }
            expect: {
                endpoint: { url: "http://bucket.name.s3.amazonaws.com" }
            }
        }
        {
            documentation: "bucket.name.multiple.dots1: isVirtualHostable (http only)"
            params: { BucketName: "bucket.name.multiple.dots1" }
            expect: {
                endpoint: { url: "http://bucket.name.multiple.dots1.s3.amazonaws.com" }
            }
        }
        {
            documentation: "-bucket-name: not isVirtualHostable (leading dash)"
            params: { BucketName: "-bucket-name" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "bucket-name-: not isVirtualHostable (trailing dash)"
            params: { BucketName: "bucket-name-" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "aa: not isVirtualHostable (< 3 characters)"
            params: { BucketName: "aa" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "'a'*64: not isVirtualHostable (> 63 characters)"
            params: { BucketName: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: ".bucket-name: not isVirtualHostable (leading dot)"
            params: { BucketName: ".bucket-name" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "bucket-name.: not isVirtualHostable (trailing dot)"
            params: { BucketName: "bucket-name." }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "192.168.5.4: not isVirtualHostable (formatted like an ip address)"
            params: { BucketName: "192.168.5.4" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "bucket-.name: not isVirtualHostable (invalid label, ends with a -)"
            params: { BucketName: "bucket-.name" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "bucket.-name: not isVirtualHostable (invalid label, starts with a -)"
            params: { BucketName: "bucket.-name" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
        {
            documentation: "bucket..name: not isVirtualHostable (consequetive dots)"
            params: { BucketName: "bucket..name" }
            expect: { error: "not isVirtualHostableS3Bucket" }
        }
    ]
)
@clientContextParams(
    BucketName: { type: "string", documentation: "docs" }
)
service FizzBuzz {}
