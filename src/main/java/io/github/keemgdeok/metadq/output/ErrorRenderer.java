package io.github.keemgdeok.metadq.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.keemgdeok.metadq.core.ReasonCode;
import io.github.keemgdeok.metadq.core.Status;

public final class ErrorRenderer {
  public String render(ReasonCode reasonCode, Exception exception, OutputFormat format) {
    String message = message(exception);
    if (format == OutputFormat.JSON) {
      try {
        return JsonSupport.mapper()
            .writeValueAsString(new ErrorDocument(1, Status.ERROR, reasonCode, message));
      } catch (JsonProcessingException renderException) {
        throw new IllegalStateException("Could not render error JSON", renderException);
      }
    }
    return "ERROR: " + message;
  }

  private static String message(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  private record ErrorDocument(int version, Status status, ReasonCode reasonCode, String message) {}
}
