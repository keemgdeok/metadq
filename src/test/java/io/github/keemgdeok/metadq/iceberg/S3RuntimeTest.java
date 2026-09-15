package io.github.keemgdeok.metadq.iceberg;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.apache.iceberg.io.FileIO;
import org.junit.jupiter.api.Test;

class S3RuntimeTest {
  @Test
  void s3FileIoIsPresentInTheRuntimeDistribution() throws Exception {
    Object instance =
        Class.forName("org.apache.iceberg.aws.s3.S3FileIO").getConstructor().newInstance();

    assertInstanceOf(FileIO.class, instance);
    ((FileIO) instance).close();
  }
}
