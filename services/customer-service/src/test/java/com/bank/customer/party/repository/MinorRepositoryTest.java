package com.bank.customer.party.repository;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyPerson;
import com.bank.customer.party.dto.MinorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PartyPersonRepository#searchMinors} 검증 — 미성년 검토 화면의 진입점.
 *
 * <p>생년월일이 기준일(YYYYMMDD)보다 큰(=더 최근) 개인만 반환하는지, null 생년월일은 제외되는지 확인한다.
 * 기준일을 명시적으로 넘겨 시간 의존성 없이 쿼리 동작만 검증한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
@DisplayName("PartyPersonRepository.searchMinors — 미성년 목록")
class MinorRepositoryTest {

    private static final Pageable FIRST_20 = PageRequest.of(0, 20);
    /** 만 19세 경계 기준일 예시 — 이 날짜 이후 출생자가 미성년. */
    private static final String THRESHOLD = "20070101";

    @Autowired
    TestEntityManager em;

    @Autowired
    PartyPersonRepository partyPersonRepository;

    private void person(String name, String birthDate) {
        Party p = Party.builder()
                .partyTypeCode(Party.TYPE_PERSONAL)
                .partyName(name)
                .partyStatusCode(Party.STATUS_ACTIVE)
                .build();
        em.persist(p);
        PartyPerson pp = PartyPerson.builder()
                .partyId(p.getPartyId())
                .birthDate(birthDate)
                .genderCode("M")
                .nationalityCode("KR")
                .isPepYn(false)
                .build();
        em.persist(pp);
    }

    @Test
    @DisplayName("기준일 이후 출생자(미성년)만 이름과 함께 반환한다")
    void onlyBornAfterThreshold() {
        person("미성년", "20100815"); // > 20070101
        person("성인",   "19990101"); // <= 20070101
        person("경계_성년", THRESHOLD); // == 기준일 → 미성년 아님(strict >)
        em.flush();

        Page<MinorResponse> result = partyPersonRepository.searchMinors(THRESHOLD, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("미성년");
        assertThat(result.getContent().get(0).birthDate()).isEqualTo("20100815");
    }

    @Test
    @DisplayName("생년월일이 없는 개인은 제외된다")
    void nullBirthDate_excluded() {
        person("미성년", "20100815");
        person("생일없음", null);
        em.flush();

        Page<MinorResponse> result = partyPersonRepository.searchMinors(THRESHOLD, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("미성년");
    }
}
