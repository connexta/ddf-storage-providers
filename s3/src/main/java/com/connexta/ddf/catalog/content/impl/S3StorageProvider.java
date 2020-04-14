/**
 * Copyright (c) Connexta
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package com.connexta.ddf.catalog.content.impl;

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.google.common.io.ByteSource;
import ddf.catalog.content.StorageException;
import ddf.catalog.content.StorageProvider;
import ddf.catalog.content.data.ContentItem;
import ddf.catalog.content.data.impl.ContentItemImpl;
import ddf.catalog.content.data.impl.ContentItemValidator;
import ddf.catalog.content.operation.CreateStorageRequest;
import ddf.catalog.content.operation.CreateStorageResponse;
import ddf.catalog.content.operation.DeleteStorageRequest;
import ddf.catalog.content.operation.DeleteStorageResponse;
import ddf.catalog.content.operation.ReadStorageRequest;
import ddf.catalog.content.operation.ReadStorageResponse;
import ddf.catalog.content.operation.StorageRequest;
import ddf.catalog.content.operation.UpdateStorageRequest;
import ddf.catalog.content.operation.UpdateStorageResponse;
import ddf.catalog.content.operation.impl.CreateStorageResponseImpl;
import ddf.catalog.content.operation.impl.DeleteStorageResponseImpl;
import ddf.catalog.content.operation.impl.ReadStorageResponseImpl;
import ddf.catalog.content.operation.impl.UpdateStorageResponseImpl;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.security.encryption.EncryptionService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.ws.rs.core.MediaType;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.codice.ddf.configuration.PropertyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** S3 Content Storage Provider. */
public class S3StorageProvider implements StorageProvider {

  private static final String DEFAULT_MIME_TYPE = MediaType.APPLICATION_OCTET_STREAM;

  private static final Logger LOGGER = LoggerFactory.getLogger(S3StorageProvider.class);

  private static final String S3_ENDPOINT_NAME = "s3Endpoint";

  private static final String S3_REGION_NAME = "s3Region";

  private static final String S3_ACCESS_KEY_NAME = "s3AccessKey";

  private static final String S3_SECRET_KEY_NAME = "s3SecretKey";

  private static final String S3_BUCKET_NAME = "s3Bucket";

  private static final String CONTENT_PREFIX_NAME = "contentPrefix";

  private static final String AWS_KMS_KEY_ID_NAME = "awsKmsKeyId";

  private static final String USE_SSE_S3_ENCRYPTION_NAME = "useSseS3Encryption";

  private final EncryptionService encryptionService;

  private String s3Endpoint;

  private String s3Region;

  private String s3Bucket;

  private String s3AccessKey;

  private String s3SecretKey;

  private String contentPrefix;

  private String awsKmsKeyId;

  private boolean useSseS3Encryption;

  private Map<String, List<Metacard>> deletionMap = new ConcurrentHashMap<>();

  private Map<String, Set<ContentItem>> updateMap = new ConcurrentHashMap<>();

  AmazonS3 amazonS3;

  public S3StorageProvider(final EncryptionService encryptionService) {
    LOGGER.info("S3 Content Storage Provider initializing...");

    this.encryptionService = encryptionService;
  }

  @Override
  public CreateStorageResponse create(CreateStorageRequest createRequest) throws StorageException {
    LOGGER.trace("ENTERING: create");

    List<ContentItem> contentItems = createRequest.getContentItems();
    List<ContentItem> createdContentItems = new ArrayList<>(createRequest.getContentItems().size());
    for (ContentItem contentItem : contentItems) {
      try {
        LOGGER.debug("Processing content item {}", contentItem.getFilename());
        if (!ContentItemValidator.validate(contentItem)) {
          LOGGER.warn("Item is not valid: {}", contentItem);
          continue;
        }
        createdContentItems.add(generateContentItem(contentItem));
      } catch (IOException e) {
        throw new StorageException(e);
      }
    }
    CreateStorageResponse response =
        new CreateStorageResponseImpl(createRequest, createdContentItems);
    updateMap.put(createRequest.getId(), createdContentItems.stream().collect(Collectors.toSet()));

    LOGGER.trace("EXITING: create");

    return response;
  }

  @Override
  public ReadStorageResponse read(ReadStorageRequest readRequest) throws StorageException {
    LOGGER.trace("ENTERING: read");

    if (readRequest.getResourceUri() == null) {
      return new ReadStorageResponseImpl(readRequest);
    }
    URI uri = readRequest.getResourceUri();
    ContentItem returnItem = readContent(uri);
    return new ReadStorageResponseImpl(readRequest, returnItem);
  }

  @Override
  public UpdateStorageResponse update(UpdateStorageRequest updateRequest) throws StorageException {
    LOGGER.trace("ENTERING: update");

    List<ContentItem> contentItems = updateRequest.getContentItems();
    List<ContentItem> updatedItems = new ArrayList<>(updateRequest.getContentItems().size());
    for (ContentItem contentItem : contentItems) {
      try {
        if (!ContentItemValidator.validate(contentItem)) {
          LOGGER.warn("Item is not valid: {}", contentItem);
          continue;
        }
        ContentItem updateItem = generateContentItem(contentItem);
        updatedItems.add(updateItem);

        if (updateItem.getMetacard().getResourceURI() == null
            && StringUtils.isBlank(contentItem.getQualifier())) {
          updateItem
              .getMetacard()
              .setAttribute(new AttributeImpl(Metacard.RESOURCE_URI, updateItem.getUri()));
          try {
            updateItem
                .getMetacard()
                .setAttribute(new AttributeImpl(Metacard.RESOURCE_SIZE, updateItem.getSize()));
          } catch (IOException e) {
            LOGGER.info(
                "Could not set size of content item [{}] on metacard [{}]",
                updateItem.getId(),
                updateItem.getMetacard().getId(),
                e);
          }
        }
      } catch (IOException | IllegalArgumentException e) {
        throw new StorageException(e);
      }
    }
    UpdateStorageResponse response = new UpdateStorageResponseImpl(updateRequest, updatedItems);
    updateMap.put(updateRequest.getId(), updatedItems.stream().collect(Collectors.toSet()));

    LOGGER.trace("EXITING: update");

    return response;
  }

  @Override
  public DeleteStorageResponse delete(DeleteStorageRequest deleteRequest) throws StorageException {
    LOGGER.trace("ENTERING: delete");

    List<Metacard> itemsToBeDeleted = new ArrayList<>();
    List<ContentItem> deletedContentItems = new ArrayList<>(deleteRequest.getMetacards().size());
    for (Metacard metacard : deleteRequest.getMetacards()) {
      LOGGER.debug("File to be deleted: {}", metacard.getId());

      ContentItem deletedContentItem =
          new ContentItemImpl(metacard.getId(), "", null, "", "", 0, metacard);

      if (!ContentItemValidator.validate(deletedContentItem)) {
        LOGGER.warn("Cannot delete invalid content item ({})", deletedContentItem);
        continue;
      }
      try {
        String contentPrefix =
            getFullContentPrefix(
                new URI(deletedContentItem.getUri()).getSchemeSpecificPart(),
                new URI(deletedContentItem.getUri()).getFragment());

        if (contentPrefix != null
            && amazonS3.listObjectsV2(s3Bucket, contentPrefix).getKeyCount() != 0) {
          deletedContentItems.add(deletedContentItem);
          itemsToBeDeleted.add(metacard);
        }
      } catch (URISyntaxException | SdkClientException e) {
        throw new StorageException("Could not delete file: " + metacard.getId(), e);
      }
    }
    deletionMap.put(deleteRequest.getId(), itemsToBeDeleted);

    DeleteStorageResponse response =
        new DeleteStorageResponseImpl(deleteRequest, deletedContentItems);
    LOGGER.trace("EXITING: delete");

    return response;
  }

  @Override
  public void commit(StorageRequest request) throws StorageException {
    if (deletionMap.containsKey(request.getId())) {
      commitDeletes(request);
    } else if (updateMap.containsKey(request.getId())) {
      commitUpdates(request);
    } else {
      LOGGER.info("Nothing to commit for request: {}", request.getId());
    }
  }

  private void commitDeletes(StorageRequest request) throws StorageException {
    List<Metacard> itemsToBeDeleted = deletionMap.get(request.getId());
    try {
      for (Metacard metacard : itemsToBeDeleted) {
        LOGGER.debug("Object to be deleted: {}", metacard.getId());
        String contentPrefix = getFullContentPrefix(metacard.getId(), "");
        for (S3ObjectSummary object :
            amazonS3.listObjectsV2(s3Bucket, contentPrefix).getObjectSummaries()) {
          amazonS3.deleteObject(s3Bucket, object.getKey());
        }
      }
    } catch (SdkClientException e) {
      throw new StorageException(e);
    } finally {
      rollback(request);
    }
  }

  private void commitUpdates(StorageRequest request) throws StorageException {
    for (ContentItem item : updateMap.get(request.getId())) {
      LOGGER.debug("Processing item: {}", item.getFilename());
      try (InputStream inputStream = item.getInputStream()) {
        String fullContentPrefix =
            getFullContentPrefix(
                new URI(item.getUri()).getSchemeSpecificPart(),
                new URI(item.getUri()).getFragment());
        String objectPath = fullContentPrefix + item.getFilename();
        LOGGER.debug("Object path: {}", objectPath);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(item.getSize());
        metadata.setContentType(item.getMimeType().toString());
        for (S3ObjectSummary object :
            amazonS3.listObjectsV2(s3Bucket, fullContentPrefix).getObjectSummaries()) {
          LOGGER.debug("Deleting object from bucket: {}, key: {}", s3Bucket, object.getKey());
          amazonS3.deleteObject(s3Bucket, object.getKey());
        }
        PutObjectRequest putObjectRequest;
        if (useSseS3Encryption) {
          metadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
          LOGGER.debug("Putting object - bucket: {}, path: {}", s3Bucket, objectPath);
          putObjectRequest = new PutObjectRequest(s3Bucket, objectPath, inputStream, metadata);
        } else {
          // Encryption method set to SSE-KMS
          SSEAwsKeyManagementParams sseAwsKeyManagementParams;
          if (StringUtils.isBlank(awsKmsKeyId)) {
            // Use default AWS managed key
            sseAwsKeyManagementParams = new SSEAwsKeyManagementParams();
          } else {
            // Use custom managed key
            sseAwsKeyManagementParams = new SSEAwsKeyManagementParams(awsKmsKeyId);
          }
          LOGGER.debug(
              "Creating put object request - bucket: {}, objectPath: {}", s3Bucket, objectPath);
          putObjectRequest =
              new PutObjectRequest(s3Bucket, objectPath, inputStream, metadata)
                  .withSSEAwsKeyManagementParams(sseAwsKeyManagementParams);
        }
        amazonS3.putObject(putObjectRequest);
      } catch (URISyntaxException | IOException | SdkClientException e) {
        throw new StorageException(e);
      } finally {
        rollback(request);
      }
    }
  }

  @Override
  public void rollback(StorageRequest request) {
    String id = request.getId();
    deletionMap.remove(id);
    updateMap.remove(id);
  }

  private ContentItem readContent(URI uri) throws StorageException {
    String contentKey = getContentItemKey(uri);
    if (StringUtils.isBlank(contentKey)) {
      LOGGER.debug("Content key is empty. Failing StorageProvider read.");
      throw new StorageException(
          "Could not get valid content key for resource URI: " + uri.toString());
    }
    String mimeType = DEFAULT_MIME_TYPE;
    String filename = FilenameUtils.getName(contentKey);
    ByteSource byteSource;
    long size = 0;
    S3Object s3Object;
    try {
      s3Object = amazonS3.getObject(s3Bucket, contentKey);
      if (s3Object == null) {
        LOGGER.debug(
            "Retrieved null S3 object from S3 for content key: {}. Failing StorageProvider read",
            contentKey);
        throw new StorageException(
            "Could not get object from S3 for content key: " + contentKey + ".");
      }
    } catch (SdkClientException ex) {
      LOGGER.debug(
          "Error getting object from S3 for content key: {}. Failing StorageProvider read.",
          contentKey,
          ex);
      throw new StorageException(
          "Could not get or read object for content key: " + contentKey + ".");
    }
    byteSource = new S3ObjectByteSource(s3Object);
    ObjectMetadata objectMetadata = s3Object.getObjectMetadata();
    if (objectMetadata != null) {
      if (StringUtils.isNotEmpty(objectMetadata.getContentType())) {
        mimeType = objectMetadata.getContentType();
      } else {
        LOGGER.debug("Problem retrieving mime type of resource; defaulting to {}.", mimeType);
      }
      if (objectMetadata.getContentLength() > 0) {
        size = objectMetadata.getContentLength();
      } else {
        LOGGER.debug("Problem retrieving size of resource; defaulting to {}.", size);
      }
    }
    return new ContentItemImpl(
        uri.getSchemeSpecificPart(), uri.getFragment(), byteSource, mimeType, filename, size, null);
  }

  String getFullContentPrefix(String id, String qualifier) {
    String prefix = contentPrefix;
    if (!contentPrefix.endsWith("/")) {
      prefix = prefix.concat("/");
    }
    prefix = prefix.concat(id.substring(0, 3) + "/" + id.substring(3, 6) + "/" + id + "/");
    if (StringUtils.isNotBlank(qualifier)) {
      prefix = prefix.concat(qualifier + "/");
    }
    LOGGER.trace("Content prefix from ({}, {}): {}", id, qualifier, prefix);
    return prefix;
  }

  private String getContentItemKey(URI uri) throws StorageException {
    List<S3ObjectSummary> summaries;
    try {
      summaries =
          amazonS3
              .listObjectsV2(
                  s3Bucket, getFullContentPrefix(uri.getSchemeSpecificPart(), uri.getFragment()))
              .getObjectSummaries();
    } catch (SdkClientException ex) {
      LOGGER.debug("Unable to get object summaries for URI: {}", uri);
      throw new StorageException(ex);
    }
    if (summaries == null || summaries.isEmpty()) {
      LOGGER.debug("Unable to get content key, as the list of object summaries is null or empty.");
      return null;
    }
    return summaries.get(0).getKey();
  }

  private ContentItem generateContentItem(ContentItem item) throws IOException {
    LOGGER.trace("ENTERING: generateContentFile");

    ContentItemImpl contentItem;

    ByteSource byteSource =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return item.getInputStream();
          }
        };
    contentItem =
        new ContentItemImpl(
            item.getId(),
            item.getQualifier(),
            byteSource,
            item.getMimeType().toString(),
            item.getFilename(),
            item.getSize(),
            item.getMetacard());

    LOGGER.trace("EXITING: generateContentFile");

    return contentItem;
  }

  public void init() {
    LOGGER.debug("Initializing Amazon S3 Client...");
    AmazonS3ClientBuilder amazonClientBuilder = AmazonS3ClientBuilder.standard();
    try {
      if (StringUtils.isBlank(s3Endpoint) || StringUtils.isBlank(s3Region)) {
        amazonS3 = amazonClientBuilder.build();
        return;
      }
      AwsClientBuilder.EndpointConfiguration endpointConfiguration =
          new AwsClientBuilder.EndpointConfiguration(s3Endpoint, s3Region);
      if (StringUtils.isNotBlank(s3AccessKey) && StringUtils.isNotBlank(s3SecretKey)) {
        amazonS3 =
            amazonClientBuilder
                .withCredentials(
                    new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(s3AccessKey, s3SecretKey)))
                .withEndpointConfiguration(endpointConfiguration)
                .build();
        return;
      }
      amazonS3 = amazonClientBuilder.withEndpointConfiguration(endpointConfiguration).build();
    } catch (SdkClientException ex) {
      LOGGER.warn(
          "Problem initializing Amazon S3 client. Please configure the S3 Storage Provider using valid properties.",
          ex.getMessage());
    }
  }

  public void update(Map<String, ?> props) {
    if (props != null) {
      if (props.get(S3_ENDPOINT_NAME) instanceof String) {
        setS3Endpoint((String) props.get(S3_ENDPOINT_NAME));
      }
      if (props.get(S3_REGION_NAME) instanceof String) {
        setS3Region((String) props.get(S3_REGION_NAME));
      }
      if (props.get(S3_ACCESS_KEY_NAME) instanceof String) {
        setS3AccessKey((String) props.get(S3_ACCESS_KEY_NAME));
      }
      if (props.get(S3_SECRET_KEY_NAME) instanceof String) {
        setS3SecretKey((String) props.get(S3_SECRET_KEY_NAME));
      }
      if (props.get(S3_BUCKET_NAME) instanceof String) {
        setS3Bucket((String) props.get(S3_BUCKET_NAME));
      }
      if (props.get(CONTENT_PREFIX_NAME) instanceof String) {
        setContentPrefix((String) props.get(CONTENT_PREFIX_NAME));
      }
      if (props.get(AWS_KMS_KEY_ID_NAME) instanceof String) {
        setAwsKmsKeyId((String) props.get(AWS_KMS_KEY_ID_NAME));
      }
      if (props.get(USE_SSE_S3_ENCRYPTION_NAME) != null) {
        setUseSseS3Encryption(Boolean.valueOf(props.get(USE_SSE_S3_ENCRYPTION_NAME).toString()));
      }
    }
    init();
  }

  public void setS3Endpoint(String s3Endpoint) {
    this.s3Endpoint = s3Endpoint;
  }

  public void setS3Region(String s3Region) {
    this.s3Region = s3Region;
  }

  public void setS3AccessKey(String s3AccessKey) {
    this.s3AccessKey = encryptionService.decryptValue(s3AccessKey);
  }

  public void setS3SecretKey(String s3SecretKey) {
    this.s3SecretKey = encryptionService.decryptValue(s3SecretKey);
  }

  public void setS3Bucket(String s3Bucket) {
    this.s3Bucket = s3Bucket;
  }

  public void setContentPrefix(String contentPrefix) {
    this.contentPrefix = PropertyResolver.resolveProperties(contentPrefix);
  }

  public void setAwsKmsKeyId(String awsKmsKeyId) {
    this.awsKmsKeyId = encryptionService.decryptValue(awsKmsKeyId);
  }

  public void setUseSseS3Encryption(boolean useSseS3Encryption) {
    this.useSseS3Encryption = useSseS3Encryption;
  }
}
