package com.dvp.settlement.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 배포된 버전과 이를 처리한 파드 이름을 돌려준다. 롤링 업데이트 중 어떤 버전의 파드가
 * 응답했는지 눈으로 확인하기 위한 엔드포인트.
 */
@RestController
public class VersionController {

    private final String version;
    private final String instance;

    public VersionController(@Value("${app.version:dev}") String version,
                             @Value("${HOSTNAME:unknown}") String instance) {
        this.version = version;
        this.instance = instance;
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("version", version, "pod", instance);
    }
}
