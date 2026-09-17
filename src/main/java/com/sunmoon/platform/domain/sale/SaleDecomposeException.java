package com.sunmoon.platform.domain.sale;

/**
 * 원본을 풀지 못했습니다. 메시지가 그대로 {@code tr_sale_log.error} 에
 * 남아서, 운영자가 무엇이 빠졌는지 보고 재처리할 수 있습니다 (§3.2).
 */
public class SaleDecomposeException extends RuntimeException {

    public SaleDecomposeException(String message) {
        super(message);
    }

    public SaleDecomposeException(String message, Throwable cause) {
        super(message, cause);
    }
}
