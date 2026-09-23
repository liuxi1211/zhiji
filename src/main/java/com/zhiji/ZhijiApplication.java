package com.zhiji;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@ConfigurationPropertiesScan
@MapperScan("com.zhiji.**.repo")
public class ZhijiApplication {

    /**
     * SQLite 与 MySQL 的一个关键差异：SQLite JDBC 不会自动创建数据库文件的父目录，
     * 目录不存在会直接抛 "path to ... does not exist"。所以必须在数据源初始化前把目录建好。
     * 放在 main 里是因为它一定早于 Spring 容器与 DataSource 的初始化。
     *
     * 目录来源必须与连接串同口径：连接串用 ${zhiji.data-dir}，Spring 的宽松绑定也认环境变量
     * ZHIJI_DATA_DIR——只认命令行参数会让「用环境变量配目录」的部署直接崩在启动期。
     */
    private static final String DEFAULT_DATA_DIR = "./data";

    public static void main(String[] args) {
        ensureDataDir(args);
        SpringApplication.run(ZhijiApplication.class, args);
    }

    private static void ensureDataDir(String[] args) {
        String dir = dataDirFromArgs(args);
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("ZHIJI_DATA_DIR");
        }
        if (dir == null || dir.isBlank()) {
            dir = DEFAULT_DATA_DIR;
        }
        try {
            Files.createDirectories(Path.of(dir));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + dir, e);
        }
    }

    private static String dataDirFromArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--zhiji.data-dir".equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }
}