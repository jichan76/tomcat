package com.example.util;

import java.sql.Connection;
import java.sql.DriverManager;

public class DBUtil {
    public static Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=C:\\Tomcat9.0\\Wallet_AppDB";
        String user = "zenvis@naver.com";
        String password = "!alstjd123!@";

        Class.forName("oracle.jdbc.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }
}
