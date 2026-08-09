package com.bank.loan.advisory.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청크 크기별 검색 품질 비교 (체계적 스윕).
 *
 * 운영의 char 기반 슬라이딩 윈도우 청커({@link DocumentIngestionService#splitChunks})를 크기
 * {400,600,800,1000,1200}자(overlap 100 고정)로 스윕하고, 실제 OpenAI 임베딩
 * (text-embedding-3-small, 1536d)으로 in-memory 코사인 검색해 Recall@k·MRR@k·nDCG@k 를 측정한다.
 *
 * 코퍼스: docs/loan-service-api-spec.md (실제 사내 문서, ~108KB).
 * 정답 라벨: 마크다운 헤딩 섹션을 골드 span 으로 사용 — 검색된 청크의 char 구간이 골드 섹션 구간과
 *           겹치면 정답으로 본다(헤딩 구조에서 자동 파생, 수작업 라벨 최소화).
 * 질의: 섹션 주제별 자연어 질문 14개(헤딩 문자열을 그대로 쓰면 너무 쉬워 의미 질의로 큐레이션).
 *
 * ⚠️ 실제 OpenAI API 를 호출하므로 OPENAI_API_KEY 가 있을 때만 실행된다(없으면 skip). 비용은
 * text-embedding-3-small 기준 1회 실행 약 $0.01 미만(전 크기 합쳐 ~1천 청크 임베딩). CI 기본 미실행.
 *
 * 실행: OPENAI_API_KEY=sk-... ./gradlew :services:loan-service:test \
 *         --tests "com.bank.loan.advisory.rag.ChunkSizeRetrievalEvalTest"
 *
 * 키 없이도 {@link #하니스_배선_오프라인_검증()} 은 항상 실행돼 문서 탐색·섹션 파싱·골드 라벨·청크
 * 오프셋 정합을 검증한다(문서 드리프트/라벨 깨짐 조기 감지). 실제 품질 측정만 키가 있을 때 실행.
 */
class ChunkSizeRetrievalEvalTest {

    private static final String MODEL = "text-embedding-3-small";
    private static final int    DIM   = 1536;

    /** 스윕할 청크 크기(문자)와 overlap(문자) — 2D 그리드. */
    private static final int[] CHUNK_SIZES = {400, 600, 800, 1000, 1200};
    private static final int[] OVERLAPS    = {0, 50, 100, 150};
    private static final int   OVERLAP     = 100;  // plumbing/참조용 운영 기본값

    private static final int K  = 5;   // 주 지표 cutoff
    private static final int K2 = 10;  // 보조 Recall cutoff

    private static final String DOC_RELATIVE = "docs/loan-service-api-spec.md";

    /** 동결된 질의셋 파일(생성기가 만들어 커밋). 있으면 이걸 우선 로드, 없으면 FALLBACK 14개. */
    private static final String QUERY_RESOURCE_FILE = "services/advisory-service/src/test/resources/chunk-eval/queries.json";
    /** 생성기가 만들 질의 개수. */
    private static final int GEN_COUNT = 50;

    // findAndRegisterModules: 클래스패스의 parameter-names 모듈을 등록해 record (역)직렬화 지원
    private static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();

    /** 자연어 질의 → 정답 섹션(헤딩 제목에 포함될 식별 문자열). */
    private record Q(String query, String goldTitleContains) {}

    private static final List<Q> FALLBACK_QUERIES = List.of(
            new Q("대출 상품 목록과 우대금리 조건을 조회하는 API", "상품·우대금리"),
            new Q("고객이 신규 대출을 신청하는 엔드포인트와 필수 항목", "대출 신청"),
            new Q("신분증 본인확인과 신용조회 절차 API", "본인확인·신용"),
            new Q("대출 심사 진행과 심사 결과 처리 API", "심사"),
            new Q("담보 가치 평가와 LTV 한도, 보증 등록 API", "담보·LTV·보증"),
            new Q("대출 관련 제출 서류 업로드 및 관리 API", "서류"),
            new Q("대출 약정 체결 후 자금 실행(드로다운) API", "약정·실행"),
            new Q("대출 회차 상환과 중도 일부 상환, 거래 역분개 API", "상환·중도상환"),
            new Q("연체 발생 시 연체 정보 조회와 처리 API", "연체"),
            new Q("금리 변경과 만기 연장, 대출 종결 처리 API", "금리변경·만기·종결"),
            new Q("대출 증명서 발급과 상태 변경 이력, 알림 조회", "증명서·상태이력·알림"),
            new Q("일별 이자 계산과 회계 요약, ECL 산출 배치", "이자·회계·ECL"),
            new Q("영업일 캘린더와 일배치 실행, 데이터 동기화", "배치·캘린더·동기화"),
            new Q("감사 로그와 긴급 접근(break-glass) API", "감사·긴급접근")
    );

    /** 헤딩 한 개 = 한 섹션. [start,end) 는 본문(다음 헤딩 전까지)의 char 구간. */
    private record Section(String title, int start, int end) {}

    /** 검색 대상 청크. text + 원문 내 [start,end). */
    private record Chunk(String text, int start, int end) {}

    /** 골드 해석 결과 — 질의와 그 정답 섹션 span 을 정렬된 쌍으로 보관. */
    private record Resolved(List<Q> queries, List<int[]> goldSpans) {}

    /** 동결된 질의셋 파일이 있으면 로드, 없으면 FALLBACK 14개. (레포 경로에서 직접 읽음 — 모듈 클래스패스 무관) */
    private static List<Q> loadQueries() {
        try {
            Path f = locateRepoRoot().resolve(QUERY_RESOURCE_FILE);
            if (!Files.exists(f)) return FALLBACK_QUERIES;
            return List.of(OM.readValue(f.toFile(), Q[].class));
        } catch (Exception e) {
            System.out.println("[loadQueries] 동결셋 로드 실패 → FALLBACK 사용: " + e.getMessage());
            return FALLBACK_QUERIES;
        }
    }

    /** 각 질의의 골드 섹션(헤딩 제목 매칭, 본문 120자 초과)을 해석. 실패 질의는 제외. */
    private static Resolved resolveGold(String content, List<Q> queries) {
        List<Section> sections = parseSections(content);
        List<Q> usable = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (Q q : queries) {
            String key = norm(q.goldTitleContains());
            // 1순위: 제목 정확 일치(엔드포인트처럼 부분문자열이 겹치는 경우 오매칭 방지)
            Section gold = sections.stream()
                    .filter(s -> norm(s.title()).equals(key) && s.end() - s.start() > 120)
                    .max((a, b) -> Integer.compare(a.end() - a.start(), b.end() - b.start()))
                    .orElse(null);
            // 2순위: 정규화 부분문자열 매칭(주제 헤딩용)
            if (gold == null) {
                gold = sections.stream()
                        .filter(s -> norm(s.title()).contains(key) && s.end() - s.start() > 120)
                        .max((a, b) -> Integer.compare(a.end() - a.start(), b.end() - b.start()))
                        .orElse(null);
            }
            if (gold != null) {
                usable.add(q);
                spans.add(new int[]{gold.start(), gold.end()});
            } else {
                unresolved.add(q.goldTitleContains());
            }
        }
        if (!unresolved.isEmpty()) {
            System.out.println("[resolveGold] 미해석 질의 골드(" + unresolved.size() + "): " + unresolved);
        }
        return new Resolved(usable, spans);
    }

    /** 제목 매칭용 정규화 — 공백·구분점(·, ·, 가운뎃점류)·괄호 제거 후 비교. */
    private static String norm(String s) {
        return s.replaceAll("[\\s·∙•・\\(\\)\\[\\]]", "");
    }

    private final RestClient openai = RestClient.builder()
            .baseUrl("https://api.openai.com")
            .defaultHeader("Authorization", "Bearer " + System.getenv("OPENAI_API_KEY"))
            .requestFactory(timeoutFactory())
            .build();

    /** 키 없이 항상 실행 — 하니스 plumbing(문서/섹션/골드/청크 오프셋) 정합 검증. */
    @Test
    void 하니스_배선_오프라인_검증() throws Exception {
        String content = Files.readString(locateDoc());
        assertThat(content.length()).as("코퍼스 충분 길이").isGreaterThan(10_000);

        List<Section> sections = parseSections(content);
        assertThat(sections).as("헤딩 섹션 파싱").isNotEmpty();

        Resolved r = resolveGold(content, loadQueries());
        assertThat(r.queries()).as("골드 섹션이 해석된 질의 수").hasSizeGreaterThanOrEqualTo(10);

        // 청크 오프셋 정합: 각 청크 text 가 원문 substring(start,end) 과 정확히 일치, 전 구간 커버
        for (int size : CHUNK_SIZES) {
            List<Chunk> chunks = chunk(content, size, OVERLAP);
            assertThat(chunks).as("size %d 청크 생성", size).isNotEmpty();
            assertThat(chunks.get(0).start()).isZero();
            assertThat(chunks.get(chunks.size() - 1).end()).isEqualTo(content.length());
            for (Chunk c : chunks) {
                assertThat(c.text()).isEqualTo(content.substring(c.start(), c.end()));
            }
        }

        // 각 골드 질의에 대해 최소 1개 청크가 골드 span 과 겹쳐야 함(800자 기준)
        List<Chunk> base = chunk(content, 800, OVERLAP);
        for (int qi = 0; qi < r.queries().size(); qi++) {
            int[] gold = r.goldSpans().get(qi);
            long hit = base.stream().filter(c -> overlaps(c, gold)).count();
            assertThat(hit).as("질의[%s] 골드 겹침 청크", r.queries().get(qi).query()).isPositive();
        }
        System.out.println("[plumbing] 코퍼스 " + content.length() + "자, 섹션 " + sections.size()
                + "개, 해석된 골드 질의 " + r.queries().size() + "개 — 정상");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void 청크크기별_검색품질_Recall_MRR_nDCG_비교() throws Exception {
        String content = Files.readString(locateDoc());
        assertThat(content.length()).as("코퍼스가 충분히 길어야 청크 크기 비교가 의미있음").isGreaterThan(10_000);

        Resolved resolved = resolveGold(content, loadQueries());
        List<Q> usableQueries = resolved.queries();
        List<int[]> goldSpans = resolved.goldSpans();
        assertThat(usableQueries).as("골드 섹션이 해석된 질의").hasSizeGreaterThanOrEqualTo(10);

        // 질의 임베딩(크기·overlap 무관 — 1회)
        float[][] queryVecs = embedAll(usableQueries.stream().map(Q::query).toList());

        System.out.println();
        System.out.println("======== 청크 크기 × overlap 검색 품질 (OpenAI " + MODEL + ", " + DIM + "d) ========");
        System.out.println("코퍼스: " + DOC_RELATIVE + " (" + content.length() + "자), 질의 " + usableQueries.size() + "개");
        System.out.printf("%-6s | %-8s | %7s | %8s | %8s | %8s | %8s%n",
                "크기", "overlap", "청크수", "Recall@5", "MRR@5", "nDCG@5", "Recall@10");
        System.out.println("-------+----------+---------+----------+----------+----------+----------");

        double[][] mrrGrid = new double[CHUNK_SIZES.length][OVERLAPS.length];
        double bestMrr = 0;
        int bestSize = CHUNK_SIZES[0], bestOverlap = OVERLAPS[0];

        for (int si = 0; si < CHUNK_SIZES.length; si++) {
            for (int oi = 0; oi < OVERLAPS.length; oi++) {
                int size = CHUNK_SIZES[si], overlap = OVERLAPS[oi];
                List<Chunk> chunks = chunk(content, size, overlap);
                float[][] chunkVecs = embedAll(chunks.stream().map(Chunk::text).toList());

                double sumRecall5 = 0, sumMrr5 = 0, sumNdcg5 = 0, sumRecall10 = 0;
                for (int qi = 0; qi < usableQueries.size(); qi++) {
                    int[] gold = goldSpans.get(qi);
                    int[] ranked = rankChunks(queryVecs[qi], chunkVecs);

                    int totalRelevant = 0;
                    for (Chunk c : chunks) if (overlaps(c, gold)) totalRelevant++;

                    sumRecall5  += recallAtK(ranked, chunks, gold, K)  ? 1 : 0;
                    sumRecall10 += recallAtK(ranked, chunks, gold, K2) ? 1 : 0;
                    sumMrr5     += reciprocalRank(ranked, chunks, gold, K);
                    sumNdcg5    += ndcgAtK(ranked, chunks, gold, K, totalRelevant);
                }
                int n = usableQueries.size();
                double recall5 = sumRecall5 / n, mrr5 = sumMrr5 / n, ndcg5 = sumNdcg5 / n, recall10 = sumRecall10 / n;
                mrrGrid[si][oi] = mrr5;

                System.out.printf("%-6d | %-8d | %7d | %8.3f | %8.3f | %8.3f | %8.3f%n",
                        size, overlap, chunks.size(), recall5, mrr5, ndcg5, recall10);

                if (mrr5 > bestMrr) { bestMrr = mrr5; bestSize = size; bestOverlap = overlap; }
            }
            if (si < CHUNK_SIZES.length - 1) System.out.println("       |          |         |          |          |          |");
        }

        // MRR@5 매트릭스 (행=크기, 열=overlap)
        System.out.println();
        System.out.println("MRR@5 매트릭스 (행=크기, 열=overlap)");
        System.out.printf("%-8s", "크기\\ov");
        for (int ov : OVERLAPS) System.out.printf(" | %6d", ov);
        System.out.println();
        for (int si = 0; si < CHUNK_SIZES.length; si++) {
            System.out.printf("%-8d", CHUNK_SIZES[si]);
            for (int oi = 0; oi < OVERLAPS.length; oi++) System.out.printf(" | %6.3f", mrrGrid[si][oi]);
            System.out.println();
        }
        System.out.println("============================================================================");
        System.out.printf("MRR@5 최적: 크기 %d자 / overlap %d (MRR@5=%.3f) — 현재 운영값 800/100 과 비교%n",
                bestSize, bestOverlap, bestMrr);
        System.out.println();

        // 와이어링/모델 정상성만 느슨하게 검증 — 절대 수치는 단정하지 않음
        assertThat(bestMrr).as("최적 (크기,overlap) 의 MRR@5 (검색 동작 정상성)").isGreaterThan(0.2);
    }

    /**
     * 질의셋 생성기 — 엔드포인트 섹션 GEN_COUNT 개를 골라 LLM(gpt-4o-mini)으로 자연어 질문을 1개씩
     * 만들고 {@value #QUERY_RESOURCE_FILE} 로 동결(커밋)한다. 평가 메서드는 이 파일을 읽으므로
     * 생성은 1회만 하면 된다(재현성). OPENAI_API_KEY 있을 때만 실행.
     *
     * 실행: OPENAI_API_KEY=sk-... ./gradlew :services:loan-service:test \
     *         --tests "com.bank.loan.advisory.rag.ChunkSizeRetrievalEvalTest.질의셋_50개_생성_동결"
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void 질의셋_50개_생성_동결() throws Exception {
        String content = Files.readString(locateDoc());
        List<Section> sections = parseSections(content);

        // 엔드포인트(백틱+HTTP메서드로 시작) 섹션 중 본문 150자 이상을 후보로
        Pattern endpoint = Pattern.compile("^`(GET|POST|PUT|PATCH|DELETE)`.*");
        List<Section> candidates = sections.stream()
                .filter(s -> endpoint.matcher(s.title()).matches() && s.end() - s.start() >= 150)
                .toList();
        assertThat(candidates).as("엔드포인트 후보").hasSizeGreaterThanOrEqualTo(GEN_COUNT);

        // 문서 전체에 고르게 GEN_COUNT 개 샘플링
        List<Section> picked = new ArrayList<>();
        double step = (double) candidates.size() / GEN_COUNT;
        for (int i = 0; i < GEN_COUNT; i++) picked.add(candidates.get((int) Math.floor(i * step)));

        List<Q> generated = new ArrayList<>(GEN_COUNT);
        for (int i = 0; i < picked.size(); i++) {
            Section s = picked.get(i);
            String body = content.substring(s.start(), Math.min(s.end(), s.start() + 1500));
            String question = chatQuestion(s.title(), body);
            generated.add(new Q(question, s.title())); // 골드 = 섹션 제목(엔드포인트 경로, 고유)
            System.out.printf("[gen %2d/%d] %s%n", i + 1, GEN_COUNT, question);
        }

        Path out = locateRepoRoot().resolve(QUERY_RESOURCE_FILE);
        Files.createDirectories(out.getParent());
        OM.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), generated);
        System.out.println("[gen] " + generated.size() + "개 질의 동결 → " + out);

        // 생성 직후 골드 해석 가능 여부 즉시 검증
        Resolved r = resolveGold(content, generated);
        assertThat(r.queries()).as("생성 질의 중 골드 해석 성공 수").hasSizeGreaterThanOrEqualTo(GEN_COUNT - 5);
    }

    /** LLM 으로 섹션 1개에 대한 자연어 질문 생성. */
    private String chatQuestion(String title, String body) {
        String system = "너는 사내 대출 시스템 API 문서를 바탕으로, 실제 사용자(행원/개발자)가 물어볼 자연어 질문을 만드는 도우미다. 질문 한 문장만 출력한다.";
        String user = """
                다음 API 문서 섹션을 읽고, 이 섹션을 찾아야 답할 수 있는 자연어 질문 1개를 한국어로 만들어라.
                규칙: (1) HTTP 메서드와 URL 경로 문자열을 그대로 쓰지 말 것 (2) 기능·목적·파라미터·제약·에러 관점에서 자연스럽게 (3) 질문 한 문장만 출력.

                # 섹션 제목
                %s

                # 섹션 본문(발췌)
                %s
                """.formatted(title, body);

        ChatResponse resp = openai.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatRequest("gpt-4o-mini", List.of(
                        new ChatMessage("system", system),
                        new ChatMessage("user", user)), 0.3))
                .retrieve()
                .body(ChatResponse.class);
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI chat 응답 비어 있음");
        }
        String q = resp.choices().get(0).message().content().trim();
        // 따옴표·불릿·코드펜스 제거
        q = q.replaceAll("^[`\"'\\-\\s]+", "").replaceAll("[`\"']+$", "").trim();
        return q;
    }

    private record ChatMessage(String role, String content) {}
    private record ChatRequest(String model, List<ChatMessage> messages, double temperature) {}
    private record ChatResponse(List<Choice> choices) {
        private record Choice(ChatMessage message) {}
    }

    // ── 청킹 (운영 splitChunks 재사용, char 오프셋 보존) ──────────────────────

    private static List<Chunk> chunk(String content, int size, int overlap) {
        List<String[]> raw = DocumentIngestionService.splitChunks(content, size, overlap);
        List<Chunk> chunks = new ArrayList<>(raw.size());
        for (String[] r : raw) {
            int start = Integer.parseInt(r[1].substring("char:".length()));
            chunks.add(new Chunk(r[0], start, start + r[0].length()));
        }
        return chunks;
    }

    // ── 헤딩 섹션 파싱 ──────────────────────────────────────────────────────

    private static List<Section> parseSections(String content) {
        Pattern p = Pattern.compile("(?m)^(#{1,6})\\s+(.*)$");
        Matcher m = p.matcher(content);
        List<Integer> level = new ArrayList<>();
        List<Integer> start = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            level.add(m.group(1).length());
            start.add(m.start());
            titles.add(m.group(2).trim());
        }
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < start.size(); i++) {
            // 섹션은 자기보다 같거나 상위(레벨 수 ≤) 헤딩 직전까지 — 하위 헤딩(컨트롤러·엔드포인트)은 본문에 포함.
            int end = content.length();
            for (int j = i + 1; j < start.size(); j++) {
                if (level.get(j) <= level.get(i)) { end = start.get(j); break; }
            }
            sections.add(new Section(titles.get(i), start.get(i), end));
        }
        return sections;
    }

    private static boolean overlaps(Chunk c, int[] gold) {
        return c.start() < gold[1] && gold[0] < c.end();
    }

    // ── 랭킹·메트릭 ─────────────────────────────────────────────────────────

    /** 코사인 유사도 내림차순으로 정렬한 청크 인덱스. */
    private static int[] rankChunks(float[] query, float[][] chunkVecs) {
        int n = chunkVecs.length;
        Integer[] idx = new Integer[n];
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            score[i] = dot(query, chunkVecs[i]); // 정규화돼 있으므로 dot == cosine
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(score[b], score[a]));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = idx[i];
        return out;
    }

    private static boolean recallAtK(int[] ranked, List<Chunk> chunks, int[] gold, int k) {
        for (int i = 0; i < Math.min(k, ranked.length); i++) {
            if (overlaps(chunks.get(ranked[i]), gold)) return true;
        }
        return false;
    }

    private static double reciprocalRank(int[] ranked, List<Chunk> chunks, int[] gold, int k) {
        for (int i = 0; i < Math.min(k, ranked.length); i++) {
            if (overlaps(chunks.get(ranked[i]), gold)) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    private static double ndcgAtK(int[] ranked, List<Chunk> chunks, int[] gold, int k, int totalRelevant) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.length); i++) {
            if (overlaps(chunks.get(ranked[i]), gold)) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        double idcg = 0;
        for (int i = 0; i < Math.min(k, totalRelevant); i++) idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    // ── OpenAI 임베딩 (배치 + L2 정규화) ────────────────────────────────────

    private float[][] embedAll(List<String> texts) {
        float[][] out = new float[texts.size()][];
        int batch = 64;
        for (int from = 0; from < texts.size(); from += batch) {
            int to = Math.min(from + batch, texts.size());
            List<String> slice = texts.subList(from, to);
            EmbeddingResponse resp = openai.post()
                    .uri("/v1/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbeddingRequest(MODEL, slice, DIM))
                    .retrieve()
                    .body(EmbeddingResponse.class);
            if (resp == null || resp.data() == null || resp.data().size() != slice.size()) {
                throw new IllegalStateException("OpenAI 임베딩 응답 이상: " + (resp == null ? "null" : resp.data()));
            }
            for (EmbeddingResponse.Datum d : resp.data()) {
                out[from + d.index()] = normalize(d.embedding());
            }
        }
        return out;
    }

    private static float[] normalize(List<Double> v) {
        float[] f = new float[v.size()];
        double norm = 0;
        for (int i = 0; i < v.size(); i++) { f[i] = v.get(i).floatValue(); norm += f[i] * f[i]; }
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < f.length; i++) f[i] /= (float) norm;
        return f;
    }

    private record EmbeddingRequest(String model, List<String> input, int dimensions) {}
    private record EmbeddingResponse(List<Datum> data) {
        private record Datum(int index, List<Double> embedding) {}
    }

    // ── 인프라 ──────────────────────────────────────────────────────────────

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(60_000);
        return f;
    }

    /** working dir 에서 위로 올라가며 docs/loan-service-api-spec.md 를 찾는다. */
    private static Path locateDoc() {
        String override = System.getProperty("chunkeval.doc");
        if (override != null && !override.isBlank()) return Paths.get(override);
        return locateRepoRoot().resolve(DOC_RELATIVE);
    }

    /** DOC_RELATIVE 가 존재하는 상위 디렉터리(레포 루트)를 찾는다. */
    private static Path locateRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve(DOC_RELATIVE))) return dir;
        }
        throw new IllegalStateException(DOC_RELATIVE + " 를 찾지 못함 — -Dchunkeval.doc=경로 로 지정하세요.");
    }
}
