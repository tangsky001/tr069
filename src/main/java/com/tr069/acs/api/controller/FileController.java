package com.tr069.acs.api.controller;

import com.tr069.acs.acs.service.CpeFileService;
import com.tr069.acs.domain.entity.CpeFile;
import com.tr069.acs.mapper.CpeFileMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设备取文件的口子。
 *
 * <p>挂在上报端口上（所有端口共用一个 Web 容器，上报口设备必然能连上），
 * 路径不在 {@code /api} 与 {@code /openapi} 下，因此不鉴权——设备下载时不会带令牌。</p>
 */
@Slf4j
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final CpeFileMapper fileMapper;
    private final CpeFileService fileService;

    /**
     * 取文件：支持断点续传（Range）与缓存校验（ETag / Last-Modified）。
     */
    @GetMapping("/{id}")
    public void download(@PathVariable Long id,
                         @RequestHeader(value = "Range", required = false) String range,
                         @RequestHeader(value = "If-None-Match", required = false) String noneMatch,
                         HttpServletResponse response) throws IOException {
        CpeFile file = fileMapper.selectById(id);
        Path path = file == null ? null : fileService.pathOf(file);
        if (file == null || path == null || !Files.isRegularFile(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long size = Files.size(path);
        // 摘要就是内容指纹，文件内容没变 ETag 就不变，设备可以放心命中缓存
        String etag = "\"" + (file.getSha256() == null ? String.valueOf(id) : file.getSha256()) + "\"";
        response.setHeader("ETag", etag);
        response.setDateHeader("Last-Modified", Files.getLastModifiedTime(path).toMillis());
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");

        if (noneMatch != null && noneMatch.replace("W/", "").contains(etag)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        long start = 0;
        long end = size - 1;
        boolean partial = false;
        String spec = range == null || !range.startsWith("bytes=") ? null : range.substring(6).trim();
        if (spec != null && !spec.isEmpty() && spec.indexOf(',') < 0) {
            try {
                int dash = spec.indexOf('-');
                if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    String endText = spec.substring(dash + 1).trim();
                    if (!endText.isEmpty()) {
                        end = Math.min(Long.parseLong(endText), size - 1);
                    }
                } else if (dash == 0) {
                    // bytes=-500：最后 500 字节
                    start = Math.max(0, size + Long.parseLong(spec.substring(1).trim()));
                }
                partial = true;
            } catch (NumberFormatException e) {
                unsatisfiable(response, size);
                return;
            }
        }
        if (start > end || start >= size) {
            unsatisfiable(response, size);
            return;
        }

        long length = end - start + 1;
        response.setStatus(partial ? 206 : 200);
        response.setContentLengthLong(length);
        if (partial) {
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        try (InputStream in = Files.newInputStream(path)) {
            if (start > 0) {
                in.skip(start);
            }
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[8192];
            long left = length;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
        } catch (IOException e) {
            // 设备下载到一半断开是常态，不必当成错误
            log.debug("文件传输中断: id={}, err={}", id, e.getMessage());
        }
    }

    /** 范围不满足：告诉设备真实长度，让它自己重算。 */
    private static void unsatisfiable(HttpServletResponse response, long size) {
        response.setStatus(416);
        response.setHeader("Content-Range", "bytes */" + size);
    }
}
