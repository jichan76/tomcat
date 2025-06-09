package com.example.attendance.servlet.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FileUtil {
    public static String readSql(String absolutePath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(absolutePath)), StandardCharsets.UTF_8);
    }
}
