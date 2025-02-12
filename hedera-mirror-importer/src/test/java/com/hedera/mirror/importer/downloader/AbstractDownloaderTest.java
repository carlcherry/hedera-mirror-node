package com.hedera.mirror.importer.downloader;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import io.findify.s3mock.S3Mock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mock;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import com.hedera.mirror.importer.FileCopier;
import com.hedera.mirror.importer.MirrorProperties;
import com.hedera.mirror.importer.addressbook.NetworkAddressBook;
import com.hedera.mirror.importer.config.MirrorNodeConfiguration;
import com.hedera.mirror.importer.domain.ApplicationStatusCode;
import com.hedera.mirror.importer.domain.HederaNetwork;
import com.hedera.mirror.importer.repository.ApplicationStatusRepository;
import com.hedera.mirror.importer.util.Utility;

public abstract class AbstractDownloaderTest {
    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected ApplicationStatusRepository applicationStatusRepository;
    @TempDir
    protected Path s3Path;
    protected S3Mock s3;
    protected FileCopier fileCopier;
    protected CommonDownloaderProperties commonDownloaderProperties;
    protected MirrorProperties mirrorProperties;
    protected NetworkAddressBook networkAddressBook;
    protected S3AsyncClient s3AsyncClient;
    protected DownloaderProperties downloaderProperties;
    protected Downloader downloader;
    protected Path validPath;
    @TempDir
    Path dataPath;

    private static void corruptFile(Path p) {
        try {
            File file = p.toFile();
            if (file.isFile()) {
                FileUtils.writeStringToFile(file, "corrupt", "UTF-8", true);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Implementation can assume that mirrorProperties and commonDownloaderProperties have been initialized.
    protected abstract DownloaderProperties getDownloaderProperties();

    // Implementations can assume that s3AsyncClient, applicationStatusRepository, networkAddressBook and
    // downloaderProperties have been initialized.
    protected abstract Downloader getDownloader();

    protected abstract Path getTestDataDir();

    boolean isSigFile(Path path) {
        return path.toString().endsWith("_sig");
    }

    @BeforeEach
    void beforeEach(TestInfo testInfo) {
        System.out.println("Before test: " + testInfo.getTestMethod().get().getName());

        initProperties();
        s3AsyncClient = (new MirrorNodeConfiguration()).s3AsyncClient(commonDownloaderProperties);
        networkAddressBook = new NetworkAddressBook(mirrorProperties);
        downloader = getDownloader();

        fileCopier = FileCopier.create(Utility.getResource("data").toPath(), s3Path)
                .from(getTestDataDir())
                .to(commonDownloaderProperties.getBucketName(), downloaderProperties.getStreamType().getPath());

        validPath = downloaderProperties.getValidPath();

        s3 = S3Mock.create(8001, s3Path.toString());
        s3.start();
    }

    @AfterEach
    void after() {
        s3.shutdown();
    }

    private void initProperties() {
        mirrorProperties = new MirrorProperties();
        mirrorProperties.setDataPath(dataPath);
        mirrorProperties.setNetwork(HederaNetwork.TESTNET);

        commonDownloaderProperties = new CommonDownloaderProperties();
        commonDownloaderProperties.setBucketName("test");
        commonDownloaderProperties.setCloudProvider(CommonDownloaderProperties.CloudProvider.LOCAL);
        commonDownloaderProperties.setAccessKey("x"); // https://github.com/findify/s3mock/issues/147
        commonDownloaderProperties.setSecretKey("x");

        downloaderProperties = getDownloaderProperties();
    }

    protected void assertNoFilesinValidPath() throws Exception {
        assertThat(Files.walk(validPath))
                .filteredOn(p -> !p.toFile().isDirectory())
                .hasSize(0);
    }

    protected void assertValidFiles(List<String> filenames) throws Exception {
        assertThat(Files.walk(validPath))
                .filteredOn(p -> !p.toFile().isDirectory())
                .hasSize(filenames.size())
                .allMatch(p -> !isSigFile(p))
                .extracting(p -> p.getFileName().toString())
                .containsAll(filenames);
    }

    protected void overwriteOnDownloadHelper(String fileName1, String fileName2, ApplicationStatusCode key)
            throws Exception {
        fileCopier.copy();
        downloader.download();
        verify(applicationStatusRepository).updateStatusValue(key, fileName1);
        verify(applicationStatusRepository).updateStatusValue(key, fileName2);
        assertValidFiles(List.of(fileName1, fileName2));

        reset(applicationStatusRepository);
        // Corrupt the downloaded signatures to test that they get overwritten by good ones on re-download.
        Files.walk(downloaderProperties.getStreamPath()).filter(this::isSigFile)
                .forEach(AbstractDownloaderTest::corruptFile);
        // fileName1 will be used to calculate marker for list request. mockS3 also returns back the marker in the
        // results. This is unlike AWS S3 which does not return back the marker.
        doReturn(fileName1).when(applicationStatusRepository).findByStatusCode(key);
        downloader.download();
        verify(applicationStatusRepository).updateStatusValue(key, fileName1);
        verify(applicationStatusRepository).updateStatusValue(key, fileName2);
        assertValidFiles(List.of(fileName1, fileName2));
    }

    @Test
    @DisplayName("Missing address book")
    void testMissingAddressBook() throws Exception {
        Files.delete(mirrorProperties.getAddressBookPath());
        fileCopier.copy();
        downloader.download();
        assertNoFilesinValidPath();
    }

    protected void testMaxDownloadItemsReached(String filename) throws Exception {
        fileCopier.copy();
        downloader.download();
        assertValidFiles(List.of(filename));
    }

    @Test
    @DisplayName("Missing signatures")
    void missingSignatures() throws Exception {
        fileCopier.filterFiles(file -> !isSigFile(file.toPath())).copy();  // only copy data files
        downloader.download();
        assertNoFilesinValidPath();
    }

    @Test
    @DisplayName("Missing data files")
    void missingDataFiles() throws Exception {
        fileCopier.filterFiles("*_sig").copy();
        downloader.download();
        assertNoFilesinValidPath();
    }

    @Test
    @DisplayName("Less than 2/3 signatures")
    void lessThanTwoThirdSignatures() throws Exception {
        fileCopier.filterDirectories("*0.0.3").filterDirectories("*0.0.4").copy();
        downloader.download();
        assertNoFilesinValidPath();
    }

    @Test
    @DisplayName("Signature doesn't match file")
    void signatureMismatch() throws Exception {
        fileCopier.copy();
        Files.walk(s3Path).filter(this::isSigFile).forEach(AbstractDownloaderTest::corruptFile);
        downloader.download();
        assertNoFilesinValidPath();
    }

    @Test
    @DisplayName("Invalid or incomplete file")
    void invalidBalanceFile() throws Exception {
        fileCopier.copy();
        Files.walk(s3Path).filter(file -> !isSigFile(file)).forEach(AbstractDownloaderTest::corruptFile);
        downloader.download();
        assertNoFilesinValidPath();
    }

    @Test
    @DisplayName("Error moving record to valid folder")
    void errorMovingFile() {
        fileCopier.copy();
        validPath.toFile().delete();
        downloader.download();
        assertThat(validPath).doesNotExist();
    }
}
