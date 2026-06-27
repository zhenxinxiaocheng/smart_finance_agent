package com.smartfinance.agent.service;

import java.nio.file.Path;

public interface SkillPackageSourceProvider {

    boolean supports(String sourceType);

    Path fetch(String sourceUri);

    String resolveVersion(String sourceUri);
}
