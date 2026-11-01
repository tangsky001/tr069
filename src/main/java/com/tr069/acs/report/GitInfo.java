package com.tr069.acs.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/** 读取构建时生成的 git.properties，取不到就返回空串 */
@Component
@PropertySource(value = "classpath:git.properties", ignoreResourceNotFound = true)
public class GitInfo {

    @Value("${git.branch:}")
    private String branch;

    @Value("${git.commit.id:}")
    private String commitId;

    @Value("${git.remote.origin.url:}")
    private String remoteUrl;

    @Value("${git.build.version:}")
    private String buildVersion;

    public String getBranch() {
        return branch;
    }

    public String getCommitId() {
        return commitId;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    /** 构建版本，即 pom 里的 version */
    public String getBuildVersion() {
        return buildVersion;
    }
}
