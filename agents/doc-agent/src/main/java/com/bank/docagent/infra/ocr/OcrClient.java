package com.bank.docagent.infra.ocr;

import com.bank.docagent.infra.ocr.dto.OcrResponse;

public interface OcrClient {
    OcrResponse extract(byte[] imageBytes, String submissionId);
}
