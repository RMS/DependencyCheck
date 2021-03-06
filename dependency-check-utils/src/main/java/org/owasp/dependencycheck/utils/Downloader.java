/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.InvalidAlgorithmParameterException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * A utility to download files from the Internet.
 *
 * @author Jeremy Long <jeremy.long@owasp.org>
 */
public final class Downloader {

    /**
     * The logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Downloader.class.getName());
    /**
     * The maximum number of redirects that will be followed when attempting to download a file.
     */
    private static final int MAX_REDIRECT_ATTEMPTS = 5;

    /**
     * Private constructor for utility class.
     */
    private Downloader() {
    }

    /**
     * Retrieves a file from a given URL and saves it to the outputPath.
     *
     * @param url the URL of the file to download
     * @param outputPath the path to the save the file to
     * @throws DownloadFailedException is thrown if there is an error downloading the file
     */
    public static void fetchFile(URL url, File outputPath) throws DownloadFailedException {
        fetchFile(url, outputPath, true);
    }

    /**
     * Retrieves a file from a given URL and saves it to the outputPath.
     *
     * @param url the URL of the file to download
     * @param outputPath the path to the save the file to
     * @param useProxy whether to use the configured proxy when downloading files
     * @throws DownloadFailedException is thrown if there is an error downloading the file
     */
    public static void fetchFile(URL url, File outputPath, boolean useProxy) throws DownloadFailedException {
        if ("file".equalsIgnoreCase(url.getProtocol())) {
            File file;
            try {
                file = new File(url.toURI());
            } catch (URISyntaxException ex) {
                final String msg = String.format("Download failed, unable to locate '%s'", url.toString());
                throw new DownloadFailedException(msg);
            }
            if (file.exists()) {
                try {
                    org.apache.commons.io.FileUtils.copyFile(file, outputPath);
                } catch (IOException ex) {
                    final String msg = String.format("Download failed, unable to copy '%s' to '%s'", url.toString(), outputPath.getAbsolutePath());
                    throw new DownloadFailedException(msg);
                }
            } else {
                final String msg = String.format("Download failed, file ('%s') does not exist", url.toString());
                throw new DownloadFailedException(msg);
            }
        } else {
            HttpURLConnection conn = null;
            try {
                LOGGER.fine(String.format("Attempting download of %s", url.toString()));
                conn = URLConnectionFactory.createHttpURLConnection(url, useProxy);
                conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
                conn.connect();
                int status = conn.getResponseCode();
                int redirectCount = 0;
                while ((status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_SEE_OTHER)
                        && MAX_REDIRECT_ATTEMPTS > redirectCount++) {
                    final String location = conn.getHeaderField("Location");
                    try {
                        conn.disconnect();
                    } finally {
                        conn = null;
                    }
                    LOGGER.fine(String.format("Download is being redirected from %s to %s", url.toString(), location));
                    conn = URLConnectionFactory.createHttpURLConnection(new URL(location), useProxy);
                    conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
                    conn.connect();
                    status = conn.getResponseCode();
                }
                if (status != 200) {
                    try {
                        conn.disconnect();
                    } finally {
                        conn = null;
                    }
                    final String msg = String.format("Error downloading file %s; received response code %s.", url.toString(), status);
                    throw new DownloadFailedException(msg);

                }
            } catch (IOException ex) {
                try {
                    if (conn != null) {
                        conn.disconnect();
                    }
                } finally {
                    conn = null;
                }
                final String msg = String.format("Error downloading file %s; unable to connect.", url.toString());
                throw new DownloadFailedException(msg, ex);
            }

            final String encoding = conn.getContentEncoding();
            BufferedOutputStream writer = null;
            InputStream reader = null;
            try {
                if (encoding != null && "gzip".equalsIgnoreCase(encoding)) {
                    reader = new GZIPInputStream(conn.getInputStream());
                } else if (encoding != null && "deflate".equalsIgnoreCase(encoding)) {
                    reader = new InflaterInputStream(conn.getInputStream());
                } else {
                    reader = conn.getInputStream();
                }

                writer = new BufferedOutputStream(new FileOutputStream(outputPath));
                final byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) > 0) {
                    writer.write(buffer, 0, bytesRead);
                }
                LOGGER.fine(String.format("Download of %s complete", url.toString()));
            } catch (IOException ex) {
                analyzeException(ex);
                final String msg = String.format("Error saving '%s' to file '%s'%nConnection Timeout: %d%nEncoding: %s%n",
                        url.toString(), outputPath.getAbsolutePath(), conn.getConnectTimeout(), encoding);
                throw new DownloadFailedException(msg, ex);
            } catch (Throwable ex) {
                final String msg = String.format("Unexpected exception saving '%s' to file '%s'%nConnection Timeout: %d%nEncoding: %s%n",
                        url.toString(), outputPath.getAbsolutePath(), conn.getConnectTimeout(), encoding);
                throw new DownloadFailedException(msg, ex);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ex) {
                        LOGGER.log(Level.FINEST, "Error closing the writer in Downloader.", ex);
                    }
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ex) {
                        LOGGER.log(Level.FINEST, "Error closing the reader in Downloader.", ex);
                    }
                }
                try {
                    conn.disconnect();
                } finally {
                    conn = null;
                }
            }
        }
    }

    /**
     * Makes an HTTP Head request to retrieve the last modified date of the given URL. If the file:// protocol is specified, then
     * the lastTimestamp of the file is returned.
     *
     * @param url the URL to retrieve the timestamp from
     * @return an epoch timestamp
     * @throws DownloadFailedException is thrown if an exception occurs making the HTTP request
     */
    public static long getLastModified(URL url) throws DownloadFailedException {
        long timestamp = 0;
        //TODO add the FTP protocol?
        if ("file".equalsIgnoreCase(url.getProtocol())) {
            File lastModifiedFile;
            try {
                lastModifiedFile = new File(url.toURI());
            } catch (URISyntaxException ex) {
                final String msg = String.format("Unable to locate '%s'", url.toString());
                throw new DownloadFailedException(msg);
            }
            timestamp = lastModifiedFile.lastModified();
        } else {
            HttpURLConnection conn = null;
            try {
                conn = URLConnectionFactory.createHttpURLConnection(url);
                conn.setRequestMethod("HEAD");
                conn.connect();
                final int t = conn.getResponseCode();
                if (t >= 200 && t < 300) {
                    timestamp = conn.getLastModified();
                } else {
                    throw new DownloadFailedException("HEAD request returned a non-200 status code");
                }
            } catch (URLConnectionFailureException ex) {
                throw new DownloadFailedException("Error creating URL Connection for HTTP HEAD request.", ex);
            } catch (IOException ex) {
                analyzeException(ex);
                throw new DownloadFailedException("Error making HTTP HEAD request.", ex);
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } finally {
                        conn = null;
                    }
                }
            }
        }
        return timestamp;
    }

    /**
     * Analyzes the IOException, logs the appropriate information for debugging purposes, and then throws a
     * DownloadFailedException that wraps the IO Exception.
     *
     * @param ex the original exception
     * @throws DownloadFailedException a wrapper exception that contains the original exception as the cause
     */
    protected static void analyzeException(IOException ex) throws DownloadFailedException {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof InvalidAlgorithmParameterException) {
                final String keystore = System.getProperty("javax.net.ssl.keyStore");
                final String version = System.getProperty("java.version");
                final String vendor = System.getProperty("java.vendor");
                LOGGER.info("Error making HTTPS request - InvalidAlgorithmParameterException");
                LOGGER.info("There appears to be an issue with the installation of Java and the cacerts."
                        + "See closed issue #177 here: https://github.com/jeremylong/DependencyCheck/issues/177");
                LOGGER.info(String.format("Java Info:%njavax.net.ssl.keyStore='%s'%njava.version='%s'%njava.vendor='%s'",
                        keystore, version, vendor));
                throw new DownloadFailedException("Error making HTTPS request. Please see the log for more details.");
            }
            cause = cause.getCause();
        }
    }
}
