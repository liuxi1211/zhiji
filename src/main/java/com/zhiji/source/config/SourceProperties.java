package com.zhiji.source.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 素材资产落盘配置（zhiji.source）。data-dir 与连接串同口径（zhiji.data-dir），
 * 图片统一落在 {data-dir}/assets/source/{itemId}/ 下，落盘即真相（暂缓清单 1.6）。
 */
@ConfigurationProperties(prefix = "zhiji.source")
public class SourceProperties {

    private String dataDir = "./data";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
