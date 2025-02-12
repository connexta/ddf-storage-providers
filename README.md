# DDF Content Storage Providers

Implementations of `ddf.catalog.content.StorageProvider`

##  S3 AWS JDK Version 1 Implementation

Creates  a bundle that can be installed into DDF 2.29.1. 

* When the bundle it loaded, it should become the registered storage provider for `org.codice.ddf.catalog.content.resource.reader.ContentResourceReader`.
* Edit the `.config` file to set AWS key and secret key, as well as bucket name, endpoint URL, and region. Region where the bucket was created must match the config settings for endpoint and region.
