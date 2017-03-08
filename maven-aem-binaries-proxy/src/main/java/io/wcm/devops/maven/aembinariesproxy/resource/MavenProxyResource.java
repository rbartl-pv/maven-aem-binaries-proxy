/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2017 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.devops.maven.aembinariesproxy.resource;

import static javax.ws.rs.core.HttpHeaders.CONTENT_LENGTH;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.Timed;

import io.wcm.devops.maven.aembinariesproxy.MavenProxyConfiguration;

/**
 * Proxies NodeJS binaries.
 */
@Path("/")
public class MavenProxyResource {

  private final MavenProxyConfiguration config;
  private final CloseableHttpClient httpClient;

  private static final Logger log = LoggerFactory.getLogger(MavenProxyResource.class);

  /**
   * @param config Configuration
   */
  public MavenProxyResource(MavenProxyConfiguration config, CloseableHttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
  }

  /**
   * Shows index page
   */
  @GET
  @Path("/")
  @Timed
  @Produces(MediaType.TEXT_HTML)
  public String getIndex() {
    return IndexPageBuilder.build(config);
  }

  /**
   * Maps POM requests simulating a Maven 2 directory structure.
   * @throws IOException
   */
  @GET
  @Path("{groupIdPath:[a-zA-Z0-9\\-\\_]+(/[a-zA-Z0-9\\-\\_]+)*}"
      + "/{artifactId:[a-zA-Z0-9\\-\\_\\.]+}"
      + "/{version:\\d+(\\.\\d+)*}"
      + "/{artifactIdFilename:[a-zA-Z0-9\\-\\_\\.]+}"
      + "-{versionFilename:\\d+(\\.\\d+)*}"
      + ".{fileExtension:pom(\\.sha1)?}")
  @Timed
  public Response getPom(
      @PathParam("groupIdPath") String groupIdPath,
      @PathParam("artifactId") String artifactId,
      @PathParam("version") String version,
      @PathParam("artifactIdFilename") String artifactIdFilename,
      @PathParam("versionFilename") String versionFilename,
      @PathParam("fileExtension") String fileExtension) throws IOException {

    String groupId = mapGroupId(groupIdPath);
    if (!validateBasicParams(groupId, artifactId, version, artifactIdFilename, versionFilename)) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    ArtifactType artifactType = getArtifactType(artifactId);

    // validate that version exists
    String url = getPomValidateUrl(artifactType, version);
    log.info("Validate file: {}", url);
    HttpHead get = new HttpHead(url);
    HttpResponse response = httpClient.execute(get);
    try {
      if (response.getStatusLine().getStatusCode() != HttpServletResponse.SC_OK) {
        return Response.status(Response.Status.NOT_FOUND).build();
      }
    }
    finally {
      EntityUtils.consumeQuietly(response.getEntity());
    }

    String xml = PomBuilder.build(groupId, artifactId, version, "pom");

    if (StringUtils.equals(fileExtension, "pom")) {
      return Response.ok(xml)
          .type(MediaType.APPLICATION_XML)
          .build();
    }
    if (StringUtils.equals(fileExtension, "pom.sha1")) {
      return Response.ok(DigestUtils.sha1Hex(xml))
          .type(MediaType.TEXT_PLAIN)
          .build();
    }
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  /**
   * Maps all requests to NodeJS binaries simulating a Maven 2 directory structure.
   */
  @GET
  @Path("{groupIdPath:[a-zA-Z0-9\\-\\_]+(/[a-zA-Z0-9\\-\\_]+)*}"
      + "/{artifactId:[a-zA-Z0-9\\-\\_\\.]+}"
      + "/{version:\\d+(\\.\\d+)*}"
      + "/{artifactIdFilename:[a-zA-Z0-9\\-\\_\\.]+}"
      + "-{versionFilename:\\d+(\\.\\d+)*}"
      + "-{os:[a-zA-Z0-9\\_]+}"
      + "-{arch:[a-zA-Z0-9\\_]+}"
      + ".{type:[a-z]+(\\.[a-z]+)*(\\.sha1)?}")
  @Timed
  public Response getBinary(
      @PathParam("groupIdPath") String groupIdPath,
      @PathParam("artifactId") String artifactId,
      @PathParam("version") String version,
      @PathParam("artifactIdFilename") String artifactIdFilename,
      @PathParam("versionFilename") String versionFilename,
      @PathParam("os") String os,
      @PathParam("arch") String arch,
      @PathParam("type") String type) throws IOException {

    String groupId = mapGroupId(groupIdPath);
    if (!validateBasicParams(groupId, artifactId, version, artifactIdFilename, versionFilename)) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    ArtifactType artifactType = getArtifactType(artifactId);
    if (artifactType != ArtifactType.NODEJS) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }

    boolean getChecksum = false;
    if (StringUtils.endsWith(type, ".sha1")) {
      getChecksum = true;
    }

    String url = buildBinaryUrl(artifactType, version, os, arch, StringUtils.removeEnd(type, ".sha1"));
    return getBinaryWithChecksumValidation(url, version, getChecksum);
  }

  /**
   * Maps all requests to NPM binaries simulating a Maven 2 directory structure.
   */
  @GET
  @Path("{groupIdPath:[a-zA-Z0-9\\-\\_]+(/[a-zA-Z0-9\\-\\_]+)*}"
      + "/{artifactId:[a-zA-Z0-9\\-\\_\\.]+}"
      + "/{version:\\d+(\\.\\d+)*}"
      + "/{artifactIdFilename:[a-zA-Z0-9\\-\\_\\.]+}"
      + "-{versionFilename:\\d+(\\.\\d+)*}"
      + ".{type:[a-z]+(\\.[a-z]+)*(\\.sha1)?}")
  @Timed
  public Response getBinary(
      @PathParam("groupIdPath") String groupIdPath,
      @PathParam("artifactId") String artifactId,
      @PathParam("version") String version,
      @PathParam("artifactIdFilename") String artifactIdFilename,
      @PathParam("versionFilename") String versionFilename,
      @PathParam("type") String type) throws IOException {

    String groupId = mapGroupId(groupIdPath);
    if (!validateBasicParams(groupId, artifactId, version, artifactIdFilename, versionFilename)) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    ArtifactType artifactType = getArtifactType(artifactId);
    if (artifactType != ArtifactType.NPM) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }

    boolean getChecksum = false;
    if (StringUtils.endsWith(type, ".sha1")) {
      getChecksum = true;
    }

    String url = buildBinaryUrl(artifactType, version, null, null, StringUtils.removeEnd(type, ".sha1"));
    return getBinary(url, version, getChecksum, null);
  }

  private Response getBinaryWithChecksumValidation(String url, String version, boolean getChecksum) throws IOException {
    // get original checksum from source directory
    Checksums checksums = getChecksums(version);
    if (checksums == null) {
      log.info("File not found: {} - no checksum file found.", url);
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    String checksum = checksums.get(url);
    if (checksum == null) {
      log.info("File not found: {} - no checksum found in checkum file.", url);
      return Response.status(Response.Status.NOT_FOUND).build();
    }

    return getBinary(url, version, getChecksum, checksum);
  }

  private Response getBinary(String url, String version, boolean getChecksum, String expectedChecksum) throws IOException {
    log.info("Proxy file: {}", url);
    HttpGet get = new HttpGet(url);
    HttpResponse response = httpClient.execute(get);
    if (response.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
      byte[] data = EntityUtils.toByteArray(response.getEntity());

      // validate checksum
      if (expectedChecksum != null) {
        String remoteChecksum = DigestUtils.sha256Hex(data);
        if (!StringUtils.equals(expectedChecksum, remoteChecksum)) {
          log.warn("Reject file: {} - checksum comparison failed - expected: {}, actual: {}", url, expectedChecksum, remoteChecksum);
          return Response.status(Response.Status.NOT_FOUND).build();
        }
      }

      if (getChecksum) {
        return Response.ok(DigestUtils.sha1Hex(data))
            .type(MediaType.TEXT_PLAIN)
            .build();
      }
      else {
        return Response.ok(data)
            .type(MediaType.APPLICATION_OCTET_STREAM)
            .header(CONTENT_LENGTH, response.containsHeader(CONTENT_LENGTH) ? response.getFirstHeader(CONTENT_LENGTH).getValue() : null)
            .build();
      }
    }
    else {
      EntityUtils.consumeQuietly(response.getEntity());
      return Response.status(Response.Status.NOT_FOUND).build();
    }
  }


  private String mapGroupId(String groupIdPath) {
    return StringUtils.replace(groupIdPath, "/", ".");
  }

  /**
   * Validate that group/artifactid are correct and version is consistent within the path.
   */
  private boolean validateBasicParams(
      String groupId,
      String artifactId,
      String version,
      String artifactIdFilename,
      String versionFilename) {
    if (!StringUtils.equals(artifactId, artifactIdFilename)) {
      return false;
    }
    if (!StringUtils.equals(version, versionFilename)) {
      return false;
    }
    if (!StringUtils.equals(groupId, config.getGroupId())) {
      return false;
    }
    if (!(StringUtils.equals(artifactId, config.getNodeJsArtifactId())
        || StringUtils.equals(artifactId, config.getNpmArtifactId()))) {
      return false;
    }
    return true;
  }

  private ArtifactType getArtifactType(String artifactId) {
    if (StringUtils.equals(artifactId, config.getNodeJsArtifactId())) {
      return ArtifactType.NODEJS;
    }
    if (StringUtils.equals(artifactId, config.getNpmArtifactId())) {
      return ArtifactType.NPM;
    }
    throw new IllegalArgumentException("Invalid artifactId: " + artifactId);
  }

  private Checksums getChecksums(String version) throws IOException {
    String url = config.getNodeJsBinariesRootUrl()
        + StringUtils.replace(config.getNodeJsChecksumUrl(), "${version}", version);
    log.info("Get file: {}", url);
    HttpGet get = new HttpGet(url);
    HttpResponse response = httpClient.execute(get);
    try {
      if (response.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
        return new Checksums(EntityUtils.toString(response.getEntity()));
      }
      else {
        return null;
      }
    }
    finally {
      EntityUtils.consumeQuietly(response.getEntity());
    }
  }

  private String getPomValidateUrl(ArtifactType artifactType, String version) {
    switch (artifactType) {
      case NODEJS:
        return config.getNodeJsBinariesRootUrl()
            + StringUtils.replace(config.getNodeJsChecksumUrl(), "${version}", version);
      case NPM:
        return config.getNodeJsBinariesRootUrl()
            + StringUtils.replace(StringUtils.replace(config.getNpmBinariesUrl(), "${version}", version), "${type}", "tgz");
      default:
        throw new IllegalArgumentException("Illegal artifact type: " + artifactType);
    }
  }

  private String buildBinaryUrl(ArtifactType artifactType, String version, String os, String arch, String type) {
    String url;
    switch (artifactType) {
      case NODEJS:
        if (StringUtils.equals(os, "windows")) {
          if (isVersion4Up(version)) {
            url = config.getNodeJsBinariesUrlWindows();
          }
          else if (StringUtils.equals(arch, "x86")) {
            url = config.getNodeJsBinariesUrlWindowsX86Legacy();
          }
          else {
            url = config.getNodeJsBinariesUrlWindowsX64Legacy();
          }
        }
        else {
          url = config.getNodeJsBinariesUrl();
        }
        break;
      case NPM:
        url = config.getNpmBinariesUrl();
        break;
      default:
        throw new IllegalArgumentException("Illegal artifact type: " + artifactType);
    }
    url = config.getNodeJsBinariesRootUrl() + url;
    url = StringUtils.replace(url, "${version}", StringUtils.defaultString(version));
    url = StringUtils.replace(url, "${os}", StringUtils.defaultString(os));
    url = StringUtils.replace(url, "${arch}", StringUtils.defaultString(arch));
    url = StringUtils.replace(url, "${type}", StringUtils.defaultString(type));
    return url;
  }

  private boolean isVersion4Up(String version) {
    DefaultArtifactVersion givenVersion = new DefaultArtifactVersion(version);
    DefaultArtifactVersion minVersion = new DefaultArtifactVersion("4.0.0");
    return givenVersion.compareTo(minVersion) >= 0;
  }

}
