package com.cifa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P1 server shell, at the ROOT package per Spring convention: @SpringBootTest
 * finds it by searching upward from any com.cifa.* test package, and the
 * default component scan covers every shell package (web / persistence /
 * config / app) without a scanBasePackages override.
 *
 * Everything under com.cifa.web / com.cifa.persistence / com.cifa.config /
 * com.cifa.app may depend on Spring; every other com.cifa package is the
 * framework-free P0 core and must never import Spring (ArchitectureTest).
 *
 * Mapper registration deliberately lives in {@link com.cifa.config.MyBatisConfig},
 * NOT here: slices like @WebMvcTest load this class as their context root, and
 * a @MapperScan here would demand a SqlSessionFactory in every slice context
 * that has no datasource.
 */
@SpringBootApplication
public class DiagnosisServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiagnosisServerApplication.class, args);
    }
}
