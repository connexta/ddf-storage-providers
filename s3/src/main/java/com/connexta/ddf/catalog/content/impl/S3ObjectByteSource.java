package com.connexta.ddf.catalog.content.impl;

import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;

public class S3ObjectByteSource extends ByteSource {

  private S3Object s3Object;

  public S3ObjectByteSource(S3Object s3Object) {
    this.s3Object = s3Object;
  }

  @Override
  public InputStream openStream() throws IOException {
    return s3Object.getObjectContent();
  }
}
