package io.github.keemgdeok.metadq.iceberg;

public final class UnsupportedFormatVersionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public UnsupportedFormatVersionException(int version) {
    super("Unsupported Iceberg format version " + version + "; metadq v0.1 supports v1 and v2");
  }
}
