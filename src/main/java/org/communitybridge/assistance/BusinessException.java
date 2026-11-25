package org.communitybridge.assistance;

public class BusinessException extends RuntimeException {
  private final String code;

  public BusinessException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static BusinessException notFound() {
    return new BusinessException("NOT_FOUND", "Record not found.");
  }
}
