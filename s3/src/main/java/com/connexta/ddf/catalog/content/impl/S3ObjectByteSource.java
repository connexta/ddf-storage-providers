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

import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;

public class S3ObjectByteSource extends ByteSource {

  private final S3Object s3Object;

  public S3ObjectByteSource(S3Object s3Object) {
    this.s3Object = s3Object;
  }

  @Override
  public InputStream openStream() throws IOException {
    return s3Object.getObjectContent();
  }
}
