package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class DnsCheckerService {

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String domain, String recordType) {
        return CompletableFuture.completedFuture(check(domain, recordType));
    }

    public Map<String, Object> check(String domain, String recordType) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            env.put(Context.PROVIDER_URL, "dns:");
            env.put("com.sun.jndi.dns.timeout.initial", "5000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{recordType});
            Attribute attr = attrs.get(recordType);

            List<String> values = new ArrayList<>();
            if (attr != null) {
                NamingEnumeration<?> e = attr.getAll();
                while (e.hasMore()) {
                    values.add(String.valueOf(e.next()));
                }
            }
            Collections.sort(values);

            result.put("success", true);
            result.put("values", values);
            ctx.close();
        } catch (Exception e) {
            result.put("success", false);
            result.put("values", List.of());
            result.put("error", e.getMessage());
            log.debug("DNS check failed for {} {}: {}", recordType, domain, e.getMessage());
        }
        return result;
    }
}
