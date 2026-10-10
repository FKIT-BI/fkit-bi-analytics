package ru.fkit.bi.analytics.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Uses proxy headers only when the direct peer matches an explicitly configured trusted CIDR. */
@Component
public class TrustedClientAddress {
  private final List<Cidr> trustedProxies;

  public TrustedClientAddress(@Value("${fkit.auth.trusted-proxy-cidrs:}") String configured) {
    trustedProxies = new ArrayList<>();
    if (!configured.isBlank()) {
      for (String cidr : configured.split(",")) trustedProxies.add(Cidr.parse(cidr.trim()));
    }
  }

  public String resolve(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    if (peer == null || !trustedProxies.stream().anyMatch(cidr -> cidr.contains(peer))) return peer == null ? "unknown" : peer;
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank()) return peer;
    // A configured proxy must overwrite this header with the original client address.
    String candidate = forwarded.split(",", 2)[0].trim();
    try { return addressLiteral(candidate).getHostAddress(); }
    catch (Exception ignored) { return peer; }
  }

  private static InetAddress addressLiteral(String value) throws Exception {
    if (value.indexOf(':') >= 0 && value.matches("[0-9A-Fa-f:.]+")) return InetAddress.getByName(value);
    if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Not an IP literal");
    byte[] octets = new byte[4];
    String[] parts = value.split("\\.");
    for (int index = 0; index < parts.length; index++) {
      int octet = Integer.parseInt(parts[index]);
      if (octet > 255) throw new IllegalArgumentException("Not an IP literal");
      octets[index] = (byte) octet;
    }
    return InetAddress.getByAddress(octets);
  }

  private record Cidr(byte[] address, int prefix) {
    static Cidr parse(String value) {
      try {
        String[] pieces = value.split("/", 2);
        byte[] address = addressLiteral(pieces[0]).getAddress();
        int prefix = pieces.length == 1 ? address.length * 8 : Integer.parseInt(pieces[1]);
        if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
        return new Cidr(address, prefix);
      } catch (Exception exception) {
        throw new IllegalArgumentException("Invalid trusted proxy CIDR", exception);
      }
    }
    boolean contains(String value) {
      try {
        byte[] candidate = addressLiteral(value).getAddress();
        if (candidate.length != address.length) return false;
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int index = 0; index < fullBytes; index++) if (candidate[index] != address[index]) return false;
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
      } catch (Exception ignored) { return false; }
    }
  }
}
