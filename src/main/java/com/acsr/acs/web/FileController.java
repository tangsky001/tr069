package com.acsr.acs.web;

import com.acsr.acs.common.Result;
import com.acsr.acs.config.AcsProperties;
import com.acsr.acs.mapper.FileStoreMapper;
import com.acsr.acs.model.entity.FileStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/** 固件 / 配置文件管理，并对外提供 Download RPC 的下载地址。 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

  private final FileStoreMapper fileStoreMapper;
  private final AcsProperties properties;
  private final WebSupport webSupport;

  @GetMapping
  public Result<List<FileStore>> list() {
    List<FileStore> files = fileStoreMapper.selectList(null);
    files.forEach(f -> f.setContent(null));
    return Result.ok(files);
  }

  @PostMapping(consumes = "multipart/form-data")
  public Result<FileStore> upload(@RequestPart("file") MultipartFile file,
                                  @RequestParam(required = false) String name,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String version,
                                  @RequestParam(required = false) String targetName,
                                  @RequestParam(required = false) Long unitTypeId,
                                  HttpServletRequest request) throws Exception {
    FileStore fs = new FileStore();
    fs.setName(name == null || name.isBlank() ? file.getOriginalFilename() : name);
    fs.setType(type == null || type.isBlank() ? "SOFTWARE" : type);
    fs.setVersion(version);
    fs.setTargetName(targetName);
    fs.setUnitTypeId(unitTypeId);
    fs.setLength(file.getSize());
    fs.setContent(file.getBytes());
    fs.setCreateTime(LocalDateTime.now());
    fileStoreMapper.insert(fs);
    fs.setContent(null);
    webSupport.audit(request, "UPLOAD_FILE", fs.getName(), String.valueOf(fs.getLength()));
    return Result.ok(fs);
  }

  @DeleteMapping("/{id}")
  public Result<?> delete(@PathVariable Long id, HttpServletRequest request) {
    fileStoreMapper.deleteById(id);
    webSupport.audit(request, "DELETE_FILE", String.valueOf(id), null);
    return Result.ok();
  }

  /** CPE 下载固件/配置文件的地址（可选 Basic 认证）。 */
  @GetMapping("/{id}/download")
  public void download(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    FileStore fs = fileStoreMapper.selectById(id);
    if (fs == null || fs.getContent() == null) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    if (properties.isFileAuthUsed() && !fileAuthOk(request)) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"ACS\"");
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    response.setContentType("application/octet-stream");
    response.setContentLength(fs.getContent().length);
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"" + (fs.getTargetName() == null ? fs.getName() : fs.getTargetName()) + "\"");
    try (OutputStream out = response.getOutputStream()) {
      out.write(fs.getContent());
      out.flush();
    }
    log.info("设备下载文件: id={}, name={}, from={}", id, fs.getName(), request.getRemoteAddr());
  }

  private boolean fileAuthOk(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Basic ")) {
      return false;
    }
    String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
    String expected = properties.getAuth().getAcsToCpeUsername() + ":"
        + (properties.getAuth().getAcsToCpePassword() == null ? "" : properties.getAuth().getAcsToCpePassword());
    return decoded.equals(expected);
  }
}
