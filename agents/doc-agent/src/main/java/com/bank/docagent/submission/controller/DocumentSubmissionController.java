package com.bank.docagent.submission.controller;

import com.bank.docagent.submission.dto.ExtractionResult;
import com.bank.docagent.submission.service.SubmissionPipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "문서 제출", description = "L1~L3 파이프라인 엔드포인트")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentSubmissionController {

    private final SubmissionPipelineService pipelineService;

    @Operation(summary = "서류 제출 및 OCR 추출")
    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExtractionResult> submit(
        @RequestParam("applicationId")          String applicationId,
        @RequestParam("docCode")                String docCode,
        @RequestParam(value = "productId",
                      defaultValue = "P001")    String productId,
        @RequestPart("file")                    MultipartFile file
    ) throws Exception {
        ExtractionResult result = pipelineService.process(applicationId, docCode, productId, file);
        return ResponseEntity.ok(result);
    }
}
