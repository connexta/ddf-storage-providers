/*
 * Copyright (c) Octo Consulting Group
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
import static ddf.catalog.data.Metacard.RESOURCE_SIZE;
import static ddf.catalog.data.Metacard.RESOURCE_URI;

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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class S3StorageProvider implements StorageProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(S3StorageProvider.class);

  private final Map<String, List<Metacard>> deletionMap = new ConcurrentHashMap<>();
  private final Map<String, Set<ContentItem>> updateMap = new ConcurrentHashMap<>();
  AmazonS3 amazonS3;
  private String contentPrefix;
  private String s3AccessKey;
  private String s3Bucket;
  private String s3Endpoint;
  private String s3Region;
  private String s3SecretKey;

  AmazonS3 amazonS3() {
    if (amazonS3 == null) {
      init();
    }
    return amazonS3;
  }

  @Override
  public void commit(StorageRequest request) throws StorageException {
    if (deletionMap.containsKey(request.getId())) {
      commitDeletes(request);
    } else if (updateMap.containsKey(request.getId())) {
      commitUpdates(request);
    } else {
      LOGGER.trace("Nothing to commit for request: {}", request.getId());
    }
  }

  private void commitDeletes(StorageRequest request) throws StorageException {
    List<Metacard> itemsToBeDeleted = deletionMap.get(request.getId());
    try {
      for (Metacard metacard : itemsToBeDeleted) {
        LOGGER.trace("Object to be deleted: {}", metacard.getId());
        String contentPrefix = getFullContentPrefix(metacard.getId(), "");
        for (S3ObjectSummary object :
            amazonS3().listObjectsV2(s3Bucket, contentPrefix).getObjectSummaries()) {
          amazonS3().deleteObject(s3Bucket, object.getKey());
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
      LOGGER.trace("Processing item: {}", item.getFilename());
      try (InputStream inputStream = item.getInputStream()) {
        String fullContentPrefix =
            getFullContentPrefix(
                new URI(item.getUri()).getSchemeSpecificPart(),
                new URI(item.getUri()).getFragment());
        String objectPath = fullContentPrefix + item.getFilename();
        LOGGER.trace("Object path: {}", objectPath);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(item.getSize());
        metadata.setContentType(item.getMimeType().toString());
        for (S3ObjectSummary object :
            amazonS3().listObjectsV2(s3Bucket, fullContentPrefix).getObjectSummaries()) {
          LOGGER.trace("Deleting object from bucket: {}, key: {}", s3Bucket, object.getKey());
          amazonS3().deleteObject(s3Bucket, object.getKey());
        }
        PutObjectRequest putObjectRequest;
        LOGGER.trace(
            "Creating put object request - bucket: {}, objectPath: {}", s3Bucket, objectPath);
        putObjectRequest =
            new PutObjectRequest(s3Bucket, objectPath, inputStream, metadata)
                .withSSEAwsKeyManagementParams(new SSEAwsKeyManagementParams());

        amazonS3().putObject(putObjectRequest);
      } catch (URISyntaxException | IOException | SdkClientException e) {
        throw new StorageException(e);
      } finally {
        rollback(request);
      }
    }
  }

  @Override
  public CreateStorageResponse create(CreateStorageRequest createRequest) throws StorageException {
    List<ContentItem> contentItems = createRequest.getContentItems();
    List<ContentItem> createdContentItems = new ArrayList<>(createRequest.getContentItems().size());
    for (ContentItem contentItem : contentItems) {
      try {
        LOGGER.trace("Processing content item {}", contentItem.getFilename());
        if (!ContentItemValidator.validate(contentItem)) {
          LOGGER.trace("Item is not valid: {}", contentItem);
          continue;
        }
        createdContentItems.add(generateContentItem(contentItem));
      } catch (IOException e) {
        throw new StorageException(e);
      }
    }
    CreateStorageResponse response =
        new CreateStorageResponseImpl(createRequest, createdContentItems);
    updateMap.put(createRequest.getId(), new HashSet<>(createdContentItems));
    return response;
  }

  private AmazonS3 createS3Client() {
    return AmazonS3ClientBuilder.standard()
        .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(s3Endpoint, s3Region))
        .withCredentials(
            new AWSStaticCredentialsProvider(new BasicAWSCredentials(s3AccessKey, s3SecretKey)))
        .build();
  }

  private String defensiveGet(Map<String, ?> props, String key) {
    return Optional.ofNullable(props.get(key)).map(Object::toString).map(String::trim).orElse(null);
  }

  @Override
  public DeleteStorageResponse delete(DeleteStorageRequest deleteRequest) throws StorageException {
    List<Metacard> itemsToBeDeleted = new ArrayList<>();
    List<ContentItem> deletedContentItems = new ArrayList<>(deleteRequest.getMetacards().size());
    for (Metacard metacard : deleteRequest.getMetacards()) {
      LOGGER.trace("File to be deleted: {}", metacard.getId());
      ContentItem deletedContentItem =
          new ContentItemImpl(metacard.getId(), "", null, "", "", 0, metacard);
      if (!ContentItemValidator.validate(deletedContentItem)) {
        LOGGER.trace("Cannot delete invalid content item ({})", deletedContentItem);
        continue;
      }
      try {
        String contentPrefix =
            getFullContentPrefix(
                new URI(deletedContentItem.getUri()).getSchemeSpecificPart(),
                new URI(deletedContentItem.getUri()).getFragment());

        if (contentPrefix != null
            && amazonS3().listObjectsV2(s3Bucket, contentPrefix).getKeyCount() != 0) {
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
    return response;
  }

  private ContentItem generateContentItem(ContentItem item) throws IOException {

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
    return contentItem;
  }

  private String getContentItemKey(URI uri) throws StorageException {
    List<S3ObjectSummary> summaries;
    try {
      summaries =
          amazonS3()
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

  String getFullContentPrefix(String id, String qualifier) {
    return Path.of(
            contentPrefix,
            id.substring(0, 3),
            id.substring(3, 6),
            id,
            Optional.ofNullable(qualifier).filter(StringUtils::isNotBlank).orElse(""))
        .toString()
        .concat("/");
  }

  public void init() {
    try {
      amazonS3 = createS3Client();
    } catch (Exception e) {
      LOGGER.warn(e.getMessage());
    }
  }

  @Override
  public ReadStorageResponse read(ReadStorageRequest readRequest) throws StorageException {

    if (readRequest.getResourceUri() == null) {
      return new ReadStorageResponseImpl(readRequest);
    }
    URI uri = readRequest.getResourceUri();
    ContentItem returnItem = readContent(uri);
    return new ReadStorageResponseImpl(readRequest, returnItem);
  }

  private ContentItem readContent(URI uri) throws StorageException {
    String contentKey = getContentItemKey(uri);
    if (StringUtils.isBlank(contentKey)) {
      throw new StorageException("Could not get valid content key for resource URI: " + uri);
    }
    String mimeType = " application/octet-stream";
    String filename = FilenameUtils.getName(contentKey);
    ByteSource byteSource;
    long size = 0;
    S3Object s3Object;
    try {
      s3Object = amazonS3().getObject(s3Bucket, contentKey);
      if (s3Object == null) {
        LOGGER.trace(
            "Retrieved null S3 object from S3 for content key: {}. Failing StorageProvider read",
            contentKey);
        throw new StorageException(
            "Could not get object from S3 for content key: " + contentKey + ".");
      }
    } catch (SdkClientException ex) {
      LOGGER.trace(
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

  @Override
  public void rollback(StorageRequest request) {
    String id = request.getId();
    deletionMap.remove(id);
    updateMap.remove(id);
  }

  @SuppressWarnings("unused" /* blueprint */)
  public void setContentPrefix(String contentPrefix) {
    this.contentPrefix = contentPrefix;
  }

  @SuppressWarnings("unused" /* blueprint */)
  public void setS3AccessKey(String s3AccessKey) {
    this.s3AccessKey = s3AccessKey;
  }

  @SuppressWarnings("unused" /* blueprint */)
  public void setS3Bucket(String s3Bucket) {
    this.s3Bucket = s3Bucket;
  }

  @SuppressWarnings("unused" /* blueprint */)
  public void setS3Endpoint(String s3Endpoint) {
    this.s3Endpoint = s3Endpoint;
  }

  @SuppressWarnings("unused" /* blueprint */)
  public void setS3Region(String s3Region) {
    this.s3Region = s3Region;
  }

  @SuppressWarnings("unused" /* blueprint */)
  public void setS3SecretKey(String s3SecretKey) {
    this.s3SecretKey = s3SecretKey;
  }

  @Override
  public UpdateStorageResponse update(UpdateStorageRequest updateRequest) throws StorageException {

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
              .setAttribute(new AttributeImpl(RESOURCE_URI, updateItem.getUri()));
          try {
            updateItem
                .getMetacard()
                .setAttribute(new AttributeImpl(RESOURCE_SIZE, updateItem.getSize()));
          } catch (IOException e) {
            LOGGER.debug(
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
    updateMap.put(updateRequest.getId(), new HashSet<>(updatedItems));
    return response;
  }

  public void update(Map<String, ?> props) {
    if (props != null) {
      contentPrefix = defensiveGet(props, "contentPrefix");
      s3AccessKey = defensiveGet(props, "s3AccessKey");
      s3Bucket = defensiveGet(props, "s3Bucket");
      s3Endpoint = defensiveGet(props, "s3Endpoint");
      s3Region = defensiveGet(props, "s3Region");
      s3SecretKey = defensiveGet(props, "s3SecretKey");
      init();
    }
  }
}
