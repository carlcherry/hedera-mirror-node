package com.hedera.mirror.importer.downloader.record;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 Hedera Hashgraph, LLC
 * ​
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
 * ‍
 */

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.ResourceUtils;

import com.hedera.mirror.importer.FileCopier;
import com.hedera.mirror.importer.domain.ApplicationStatusCode;
import com.hedera.mirror.importer.downloader.AbstractDownloaderTest;
import com.hedera.mirror.importer.downloader.Downloader;
import com.hedera.mirror.importer.downloader.DownloaderProperties;
import com.hedera.mirror.importer.util.Utility;

@ExtendWith(MockitoExtension.class)
public class RecordFileDownloaderTest extends AbstractDownloaderTest {

    @Override
    protected DownloaderProperties getDownloaderProperties() {
        DownloaderProperties properties = new RecordDownloaderProperties(mirrorProperties, commonDownloaderProperties);
        properties.init();
        return properties;
    }

    @Override
    protected Downloader getDownloader() {
        return new RecordFileDownloader(s3AsyncClient, applicationStatusRepository, networkAddressBook,
                (RecordDownloaderProperties) downloaderProperties);
    }

    @Override
    protected Path getTestDataDir() {
        return Paths.get("recordstreams", "v2");
    }

    @Test
    @DisplayName("Download and verify V1 files")
    void downloadV1() throws Exception {
        Path addressBook = ResourceUtils.getFile("classpath:addressbook/test-v1").toPath();
        mirrorProperties.setAddressBookPath(addressBook);
        fileCopier = FileCopier.create(Utility.getResource("data").toPath(), s3Path)
                .from(downloaderProperties.getStreamType().getPath(), "v1")
                .to(commonDownloaderProperties.getBucketName(), downloaderProperties.getStreamType().getPath());
        fileCopier.copy();

        downloader.download();

        verify(applicationStatusRepository).updateStatusValue(
                ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE, "2019-07-01T14:13:00.317763Z.rcd");
        verify(applicationStatusRepository).updateStatusValue(
                ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE, "2019-07-01T14:29:00.302068Z.rcd");
        verify(applicationStatusRepository, times(2)).updateStatusValue(
                eq(ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE_HASH), any());
        assertValidFiles(List.of("2019-07-01T14:13:00.317763Z.rcd", "2019-07-01T14:29:00.302068Z.rcd"));
    }

    @Test
    @DisplayName("Download and verify V2 files")
    void downloadV2() throws Exception {
        fileCopier.copy();
        downloader.download();
        verify(applicationStatusRepository).updateStatusValue(
                ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE, "2019-08-30T18_10_00.419072Z.rcd");
        verify(applicationStatusRepository).updateStatusValue(
                ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE, "2019-08-30T18_10_05.249678Z.rcd");
        verify(applicationStatusRepository, times(2)).updateStatusValue(
                eq(ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE_HASH), any());
        assertValidFiles(List.of("2019-08-30T18_10_05.249678Z.rcd", "2019-08-30T18_10_00.419072Z.rcd"));
    }

    @Test
    @DisplayName("Max download items reached")
    void maxDownloadItemsReached() throws Exception {
        ((RecordDownloaderProperties) downloaderProperties).setBatchSize(1);
        testMaxDownloadItemsReached("2019-08-30T18_10_00.419072Z.rcd");
    }

    @Test
    @DisplayName("overwrite on download")
    void overwriteOnDownload() throws Exception {
        overwriteOnDownloadHelper("2019-08-30T18_10_00.419072Z.rcd", "2019-08-30T18_10_05.249678Z.rcd",
                ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE);
    }

    @Test
    @DisplayName("Doesn't match last valid hash")
    void hashMismatchWithPrevious() throws Exception {
        String filename = "2019-08-30T18_10_05.249678Z.rcd";
        doReturn("2019-07-01T14:12:00.000000Z.rcd").when(applicationStatusRepository)
                .findByStatusCode(ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE);
        doReturn("123").when(applicationStatusRepository)
                .findByStatusCode(ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE_HASH);
        fileCopier.filterFiles(filename + "*").copy(); // Skip first file with zero hash
        downloader.download();
        assertNoFilesinValidPath();
    }

    @Test
    @DisplayName("Bypass previous hash mismatch")
    void hashMismatchWithBypass() throws Exception {
        String filename = "2019-08-30T18_10_05.249678Z.rcd";
        doReturn("2019-07-01T14:12:00.000000Z.rcd").when(applicationStatusRepository)
                .findByStatusCode(ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE);
        doReturn("123").when(applicationStatusRepository)
                .findByStatusCode(ApplicationStatusCode.LAST_VALID_DOWNLOADED_RECORD_FILE_HASH);
        doReturn("2019-09-01T00:00:00.000000Z.rcd").when(applicationStatusRepository)
                .findByStatusCode(ApplicationStatusCode.RECORD_HASH_MISMATCH_BYPASS_UNTIL_AFTER);
        fileCopier.filterFiles(filename + "*").copy(); // Skip first file with zero hash
        downloader.download();
        assertValidFiles(List.of(filename));
    }
}
