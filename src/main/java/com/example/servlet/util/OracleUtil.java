package com.example.servlet.util;

import java.sql.Connection;
import java.sql.DriverManager;

public class OracleUtil {

    public static Connection getConnection() throws Exception {
        // TNS_ADMIN 경로를 명시적으로 설정
        System.setProperty("oracle.net.tns_admin", "/opt/wallet");

        String url = "jdbc:oracle:thin:@appdb_high"; // ← 이건 tnsnames.ora에서 매핑되는 이름
        String user = "ADMIN";
        String password = "NewPassword123!";

        Class.forName("oracle.jdbc.OracleDriver"); // 최신 드라이버는 oracle.jdbc.OracleDriver
        return DriverManager.getConnection(url, user, password);
    }
}