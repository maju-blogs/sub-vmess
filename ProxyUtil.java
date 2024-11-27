package org.m.web.util;

import cn.hutool.core.codec.Base64Decoder;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <desc>
 *
 * </desc>
 *
 * @author maju
 * @createDate 2024/11/20
 */
@Slf4j
@EnableScheduling
@Component
public class ProxyUtil {
    //    private static final String[] urls = new String[]{"https://gh.tryxd.cn/https://raw.githubusercontent.com/leetomlee123/freenode/main/README.md"};
    private static final List<String> urls = new ArrayList<>();
    private static final String VMESS_PREFIX = "vmess://";
    static ExecutorService executor = Executors.newFixedThreadPool(10);
    static List<String> vmessList = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<ProxyNode> proxyNode = getProxyNode();
        proxyNode = proxyNode.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(item -> item.getIp() + item.getPort(), Function.identity(), (p1, p2) -> p1),
                        map -> new ArrayList<>(map.values())
                ));
        List<ProxyNode> result = testRate(proxyNode);
        List<ProxyNode> collect = result.stream().sorted(Comparator.comparing(ProxyNode::getRate)).collect(Collectors.toList());
        for (ProxyNode node : collect) {
            log.info(node.getVmess());
        }
    }

    public static String getVmess() {
        String line = System.getProperty("line.separator");
        String result = "";
        for (String s : vmessList) {
            result += s + line;
        }
        return result;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void run() {
        File file = new File("vmess.conf");
        List<String> vmess = FileUtil.readLines(file, "utf-8");
        log.info("读取文件：{},路径：{}", JSON.toJSONString(vmess), file.getAbsolutePath());
        urls.clear();
        if (CollUtil.isNotEmpty(vmess)) {
            for (String s : vmess) {
                urls.add(s.trim());
            }
        }
        List<ProxyNode> proxyNode = getProxyNode();
        proxyNode = proxyNode.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(item -> item.getIp() + item.getPort(), Function.identity(), (p1, p2) -> p1),
                        map -> new ArrayList<>(map.values())
                ));
        List<ProxyNode> result = null;
        try {
            result = testRate(proxyNode);
            List<ProxyNode> collect = result.stream().sorted(Comparator.comparing(ProxyNode::getRate)).collect(Collectors.toList());
            vmessList.clear();
            for (ProxyNode node : collect) {
                vmessList.add(node.getVmess());
            }
        } catch (Exception e) {
        }
    }

    public static List<ProxyNode> testRate(List<ProxyNode> nodes) throws ExecutionException, InterruptedException {
        List<ProxyNode> result = new ArrayList<>();
        ConcurrentLinkedQueue<ProxyNode> queue = new ConcurrentLinkedQueue<>();
        queue.addAll(nodes);
        List<CompletableFuture> allFuture = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int finalI = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                TimeInterval timer = DateUtil.timer();
                while (true) {
                    ProxyNode poll = queue.poll();
                    if (null == poll) {
                        return;
                    }
                    try {
                        Socket clientSocket = new Socket();
                        SocketAddress socketAddress = new InetSocketAddress(poll.getIp(), poll.getPort());
                        clientSocket.connect(socketAddress);
                        clientSocket.setSoTimeout(1000);
                        boolean connected = clientSocket.isConnected();
                        if (!connected) {
//                            log.error("失败，线程{},ip:{},端口:{},速度:{}", "线程" + finalI, poll.getIp(), poll.getPort(), -1);
                            continue;
                        }
                        clientSocket.close();
                        long l = timer.intervalRestart();
                        poll.setRate(l);
//                        log.info("成功，线程{},ip:{},端口:{},速度:{}", "线程" + finalI, poll.getIp(), poll.getPort(), poll.getRate());
                        result.add(poll);
                    } catch (IOException e) {
                        timer.intervalRestart();
//                        log.error("失败，线程{},ip:{},端口:{},速度:{}", "线程" + finalI, poll.getIp(), poll.getPort(), -1);
                    }
                }
            }, executor);
            allFuture.add(future);
        }
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(allFuture.stream().toArray(CompletableFuture[]::new));
        allFutures.thenRun(() -> {
            log.info("测试完成");
        }).get();
        return result;
    }

    public static List<ProxyNode> getProxyNode() {
        Pattern p = Pattern.compile(VMESS_PREFIX + "(.*)");
        List<ProxyNode> nodes = new ArrayList<>();
        for (String url : urls) {
            try {
                HttpRequest httpRequest = HttpUtil.createGet(url).timeout(50000);
                HttpResponse execute = httpRequest.execute();
                if (execute.isOk()) {
                    String body = execute.body();
                    Matcher matcher = p.matcher(body);
                    while (matcher.find()) {
                        String group = matcher.group(1);
                        try {
                            String decodeStr = Base64Decoder.decodeStr(group);
                            JSONObject jsonObject = JSONObject.parseObject(decodeStr);
                            String ip = jsonObject.getString("add");
                            Integer port = jsonObject.getInteger("port");
                            ProxyNode node = new ProxyNode(ip, port, VMESS_PREFIX + group);
                            nodes.add(node);
                        } catch (Exception e) {
                        }
                    }
                }
            } catch (Exception e) {
            }
        }
        return nodes;
    }

}

@Data
class ProxyNode {
    public ProxyNode(String ip, Integer port, String vmess) {
        this.ip = ip;
        this.port = port;
        this.vmess = vmess;
    }

    private String ip;
    private Integer port;
    private String vmess;
    private long rate;
}
