package com.bank.loan.document.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 신청서류 원본 바이트 보관소.
 *
 * <p>구현은 두 가지이고 {@code loan.document.storage.type} 으로 고른다.
 * <ul>
 *   <li>{@code filesystem} (기본) — 로컬 디렉터리. 개발·테스트, 그리고 오브젝트 스토리지를
 *       띄우지 않는 단일 노드 배포용.</li>
 *   <li>{@code s3} — MinIO/S3 호환 오브젝트 스토리지. 다중 인스턴스 배포에서는 이쪽이어야 한다.
 *       파일시스템은 인스턴스마다 따로 놀아서 업로드한 노드가 아닌 곳으로 다운로드 요청이
 *       가면 실패한다.</li>
 * </ul>
 *
 * <p>기본값이 filesystem 인 이유: compose 의 MinIO 가 {@code profiles: ["doc"]} 라
 * 기본 스택에는 뜨지 않는다. loan-service 가 무조건 오브젝트 스토리지를 요구하면
 * 기본 스택이 기동하지 못한다.
 *
 * <p>객체 키는 docId 에서 결정된다({@code <docId>.bin}). 경로를 DB 에 들고 있지 않으므로
 * 스키마와 실제 저장물이 어긋날 여지가 없고, 구현을 바꿔도 키 규칙은 그대로다.
 */
public interface LoanDocumentStorage {

    /** 업로드 원본을 보관한다. 같은 docId 로 다시 쓰면 덮어쓴다. */
    void store(Long docId, MultipartFile file);

    /** 보관된 원본을 읽는다. 없으면 서류 없음(LOAN_041)으로 취급한다. */
    byte[] load(Long docId);

    /**
     * 원본을 파기한다. 보존기한 경과 정리 배치가 쓴다.
     *
     * <p>이미 없는 객체를 지우는 것은 오류가 아니다 — 배치 재실행·부분 실패 후 재시도에서
     * 같은 대상을 다시 만나는 일이 정상이므로 멱등해야 한다.
     */
    void delete(Long docId);

    /** 구현 공통 객체 키 규칙. */
    static String objectKey(Long docId) {
        return docId + ".bin";
    }
}
