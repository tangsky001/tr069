import java.sql.*;

/** 临时脚本：核对设备台账里的 SN / OUI 存值。用完即删。 */
public class QueryDevice {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/tr_acs?useUnicode=true&characterEncoding=UTF-8"
                + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection c = DriverManager.getConnection(url, "root", "Qq123456")) {
            System.out.println("===== 匹配 CIOT / F09008 / 534B 的台账 =====");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sn, oui, isp, isp_version, device_type, room_no FROM cpe_device "
                            + "WHERE sn LIKE ? OR sn LIKE ? OR sn LIKE ? OR room_no = ? LIMIT 10")) {
                ps.setString(1, "%CIOT%");
                ps.setString(2, "%F09008%");
                ps.setString(3, "%534B%");
                ps.setString(4, "8701");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        System.out.println("sn=" + rs.getString(1) + " | oui=" + rs.getString(2)
                                + " | isp=" + rs.getString(3) + " | ispVersion=" + rs.getString(4)
                                + " | deviceType=" + rs.getString(5) + " | room=" + rs.getString(6));
                    }
                }
            }
            System.out.println("===== 前 5 条台账（看 sn 与 oui 的形态）=====");
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT sn, oui, isp, room_no FROM cpe_device ORDER BY id DESC LIMIT 5")) {
                while (rs.next()) {
                    System.out.println("sn=" + rs.getString(1) + " | oui=" + rs.getString(2)
                            + " | isp=" + rs.getString(3) + " | room=" + rs.getString(4));
                }
            }
        }
    }
}
