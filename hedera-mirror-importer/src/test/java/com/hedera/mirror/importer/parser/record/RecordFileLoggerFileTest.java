package com.hedera.mirror.importer.parser.record;

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

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.RealmID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ShardID;
import com.hederahashgraph.api.proto.java.SystemDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TimestampSeconds;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;

import java.nio.file.Path;
import java.time.Instant;
import javax.annotation.Resource;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.jdbc.Sql;

import com.hedera.mirror.importer.MirrorProperties;
import com.hedera.mirror.importer.addressbook.NetworkAddressBook;
import com.hedera.mirror.importer.domain.Entities;
import com.hedera.mirror.importer.domain.FileData;
import com.hedera.mirror.importer.util.Utility;

// Class manually commits so have to manually cleanup tables
@Sql(executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD, scripts = "classpath:db/scripts/cleanup.sql")
@Sql(executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD, scripts = "classpath:db/scripts/cleanup.sql")
public class RecordFileLoggerFileTest extends AbstractRecordFileLoggerTest {

    //TODO: The following are not yet saved to the mirror node database
    // transactionBody.getTransactionFee()
    // transactionBody.getTransactionValidDuration()
    // transaction.getSigMap()
    // transactionBody.getNewRealmAdminKey();
    // record.getTransactionHash();

    private static final FileID fileId = FileID.newBuilder().setShardNum(0).setRealmNum(0).setFileNum(1001).build();
    private static final String realmAdminKey =
            "112212200aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92";
    private static final String memo = "File test memo";

    @TempDir
    Path dataPath;

    @Resource
    private MirrorProperties mirrorProperties;

    @Resource
    private NetworkAddressBook networkAddressBook;

    @BeforeEach
    void before() throws Exception {
        mirrorProperties.setDataPath(dataPath);
        parserProperties.setPersistFiles(true);
        parserProperties.setPersistSystemFiles(true);
        parserProperties.setPersistCryptoTransferAmounts(true);
        parserProperties.init();

        assertTrue(RecordFileLogger.start());
        Assertions.assertEquals(RecordFileLogger.INIT_RESULT.OK, RecordFileLogger.initFile("TestFile"));
    }

    @AfterEach
    void after() {
        RecordFileLogger.finish();
    }

    @Test
    void fileCreate() throws Exception {

        Transaction transaction = fileCreateTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileCreateTransactionBody fileCreateTransactionBody = transactionBody.getFileCreate();
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);
        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileCreateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileCreateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileCreateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileCreateDoNotPersist() throws Exception {
        parserProperties.setPersistFiles(false);
        parserProperties.setPersistSystemFiles(false);
        Transaction transaction = fileCreateTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);
        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)
        );
    }

    @Test
    void fileCreatePersistSystemPositive() throws Exception {
        parserProperties.setPersistFiles(false);
        Transaction transaction = fileCreateTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileCreateTransactionBody fileCreateTransactionBody = transactionBody.getFileCreate();
        TransactionRecord record = transactionRecord(transactionBody, FileID.newBuilder().setShardNum(0)
                .setRealmNum(0).setFileNum(10).build());

        RecordFileLogger.storeRecord(transaction, record);
        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileCreateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileCreateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileCreateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileCreatePersistSystemNegative() throws Exception {
        parserProperties.setPersistFiles(false);
        Transaction transaction = fileCreateTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileCreateTransactionBody fileCreateTransactionBody = transactionBody.getFileCreate();
        TransactionRecord record = transactionRecord(transactionBody, FileID.newBuilder().setShardNum(0)
                .setRealmNum(0).setFileNum(2000).build());

        RecordFileLogger.storeRecord(transaction, record);
        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileCreateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileCreateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @ParameterizedTest(name = "with {0} s and expected {1} ns")
    @CsvSource({
            "9223372036854775807, 9223372036854775807",
            "31556889864403199, 9223372036854775807",
            "-9223372036854775808, -9223372036854775808",
            "-1000000000000000000, -9223372036854775808"
    })
    void fileCreateExpirationTimeOverflow(long seconds, long expectedNanosTimestamp) throws Exception {
        final Transaction transaction = fileCreateTransaction(Timestamp.newBuilder().setSeconds(seconds).build());
        final TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        final TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);
        RecordFileLogger.completeFile("", "");

        var dbTransaction = transactionRepository.findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        var dbAccountEntity = entityRepository.findById(dbTransaction.getEntityId()).get();
        assertEquals(expectedNanosTimestamp, dbAccountEntity.getExpiryTimeNs());
    }

    @Test
    void fileAppendToExisting() throws Exception {

        // first create the file
        Transaction fileCreateTransaction = fileCreateTransaction();
        TransactionBody createTransactionBody = TransactionBody.parseFrom(fileCreateTransaction.getBodyBytes());
        TransactionRecord recordCreate = transactionRecord(createTransactionBody);

        RecordFileLogger.storeRecord(fileCreateTransaction, recordCreate);

        // now append
        Transaction transaction = fileAppendTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileAppendTransactionBody fileAppendTransactionBody = transactionBody.getFileAppend();
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(2, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(6, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(2, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // file data
                , () -> assertArrayEquals(fileAppendTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNotNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileAppendToNew() throws Exception {

        Transaction transaction = fileAppendTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileAppendTransactionBody fileAppendTransactionBody = transactionBody.getFileAppend();
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // file data
                , () -> assertArrayEquals(fileAppendTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileAppendToSystemFile() throws Exception {

        Transaction transaction = fileAppendTransaction(FileID.newBuilder().setShardNum(0).setRealmNum(0)
                .setFileNum(10).build());
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileAppendTransactionBody fileAppendTransactionBody = transactionBody.getFileAppend();
        TransactionRecord record = transactionRecord(transactionBody, FileID.newBuilder().setShardNum(0)
                .setRealmNum(0).setFileNum(10).build());

        parserProperties.setPersistFiles(true);
        parserProperties.setPersistSystemFiles(true);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // file data
                , () -> assertArrayEquals(fileAppendTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateAllToExisting() throws Exception {

        // first create the file
        Transaction fileCreateTransaction = fileCreateTransaction();
        TransactionBody createTransactionBody = TransactionBody.parseFrom(fileCreateTransaction.getBodyBytes());
        TransactionRecord recordCreate = transactionRecord(createTransactionBody);

        RecordFileLogger.storeRecord(fileCreateTransaction, recordCreate);

        // now update
        Transaction transaction = fileUpdateAllTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(2, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(6, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(2, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileUpdateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileUpdateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileUpdateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateAllToExistingFailedTransaction() throws Exception {

        // first create the file
        Transaction fileCreateTransaction = fileCreateTransaction();
        TransactionBody createTransactionBody = TransactionBody.parseFrom(fileCreateTransaction.getBodyBytes());
        TransactionRecord recordCreate = transactionRecord(createTransactionBody);
        FileCreateTransactionBody fileCreateTransactionBody = createTransactionBody.getFileCreate();

        RecordFileLogger.storeRecord(fileCreateTransaction, recordCreate);

        // now update
        Transaction transaction = fileUpdateAllTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody,
                ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(2, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(6, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileCreateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileCreateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileCreateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileAppendToAddressBook() throws Exception {

        networkAddressBook.update(new byte[0]);

        parserProperties.setPersistFiles(true);
        parserProperties.setPersistSystemFiles(true);

        Transaction transaction = fileAppendTransaction(FileID.newBuilder().setShardNum(0).setRealmNum(0)
                .setFileNum(102).build());
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileAppendTransactionBody fileAppendTransactionBody = transactionBody.getFileAppend();
        TransactionRecord record = transactionRecord(transactionBody, FileID.newBuilder().setShardNum(0)
                .setRealmNum(0).setFileNum(102).build());

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // file data
                , () -> assertArrayEquals(fileAppendTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                , () -> assertArrayEquals(fileAppendTransactionBody.getContents().toByteArray(),
                        FileUtils.readFileToByteArray(mirrorProperties.getAddressBookPath().toFile())
                )

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateAllToNew() throws Exception {

        Transaction transaction = fileUpdateAllTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileUpdateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileUpdateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileUpdateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateContentsToExisting() throws Exception {

        // first create the file
        Transaction fileCreateTransaction = fileCreateTransaction();
        TransactionBody createTransactionBody = TransactionBody.parseFrom(fileCreateTransaction.getBodyBytes());
        TransactionRecord recordCreate = transactionRecord(createTransactionBody);

        RecordFileLogger.storeRecord(fileCreateTransaction, recordCreate);

        // now update
        Transaction transaction = fileUpdateContentsTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(2, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(6, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(2, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertNotNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNotNull(dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileUpdateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateContentsToNew() throws Exception {

        Transaction transaction = fileUpdateContentsTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileUpdateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateExpiryToExisting() throws Exception {

        // first create the file
        Transaction fileCreateTransaction = fileCreateTransaction();
        TransactionBody createTransactionBody = TransactionBody.parseFrom(fileCreateTransaction.getBodyBytes());
        TransactionRecord recordCreate = transactionRecord(createTransactionBody);

        RecordFileLogger.storeRecord(fileCreateTransaction, recordCreate);

        // now update
        Transaction transaction = fileUpdateExpiryTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(2, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(6, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                //TODO: Review below with issue #294, probably should be 1
                , () -> assertEquals(2, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileUpdateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertNotNull(dbFileEntity.getKey())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateExpiryToNew() throws Exception {

        Transaction transaction = fileUpdateExpiryTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                //TODO: Review below with issue #294, probably should be 0
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileUpdateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getKey())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateKeysToExisting() throws Exception {

        // first create the file
        Transaction fileCreateTransaction = fileCreateTransaction();
        TransactionBody createTransactionBody = TransactionBody.parseFrom(fileCreateTransaction.getBodyBytes());
        TransactionRecord recordCreate = transactionRecord(createTransactionBody);

        RecordFileLogger.storeRecord(fileCreateTransaction, recordCreate);

        // now update
        Transaction transaction = fileUpdateKeysTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(2, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(6, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                //TODO: Review below with issue #294, probably should be 1
                , () -> assertEquals(2, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertNotNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertArrayEquals(fileUpdateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateKeysToNew() throws Exception {

        Transaction transaction = fileUpdateKeysTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                //TODO: Review below with issue #294, probably should be 0
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertArrayEquals(fileUpdateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateAllToNewSystem() throws Exception {

        Transaction transaction = fileUpdateAllTransaction(FileID.newBuilder().setShardNum(0).setRealmNum(0)
                .setFileNum(10).build());
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();
        TransactionRecord record = transactionRecord(transactionBody, FileID.newBuilder().setShardNum(0)
                .setRealmNum(0).setFileNum(10).build());

        parserProperties.setPersistFiles(true);
        parserProperties.setPersistSystemFiles(true);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileUpdateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileUpdateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileUpdateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileUpdateAddressBook() throws Exception {

        Transaction transaction = fileUpdateAllTransaction(FileID.newBuilder().setShardNum(0).setRealmNum(0)
                .setFileNum(102).build());
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        FileUpdateTransactionBody fileUpdateTransactionBody = transactionBody.getFileUpdate();
        TransactionRecord record = transactionRecord(transactionBody, FileID.newBuilder().setShardNum(0)
                .setRealmNum(0).setFileNum(102).build());

        parserProperties.setPersistFiles(true);
        parserProperties.setPersistSystemFiles(true);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();
        FileData dbfileData = fileDataRepository.findById(dbTransaction.getConsensusNs()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getSeconds(), dbFileEntity
                        .getExpiryTimeSeconds())
                , () -> assertEquals(fileUpdateTransactionBody.getExpirationTime().getNanos(), dbFileEntity
                        .getExpiryTimeNanos())
                , () -> assertEquals(Utility
                        .timeStampInNanos(fileUpdateTransactionBody.getExpirationTime()), dbFileEntity
                        .getExpiryTimeNs())
                , () -> assertArrayEquals(fileUpdateTransactionBody.getKeys().toByteArray(), dbFileEntity.getKey())

                // file data
                , () -> assertArrayEquals(fileUpdateTransactionBody.getContents().toByteArray(), dbfileData
                        .getFileData())

                // address book file checks
                , () -> assertArrayEquals(fileUpdateTransactionBody.getContents().toByteArray(),
                        FileUtils.readFileToByteArray(mirrorProperties.getAddressBookPath().toFile())
                )
                // Additional entity checks
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
                , () -> assertFalse(dbFileEntity.isDeleted())
        );
    }

    @Test
    void fileDeleteToExisting() throws Exception {

        // first create the file
        Transaction fileCreateTransaction = fileCreateTransaction();
        TransactionBody createTransactionBody = TransactionBody.parseFrom(fileCreateTransaction.getBodyBytes());
        TransactionRecord recordCreate = transactionRecord(createTransactionBody);

        RecordFileLogger.storeRecord(fileCreateTransaction, recordCreate);

        // now update
        Transaction transaction = fileDeleteTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(transaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(transaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(2, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(6, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(1, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertTrue(dbFileEntity.isDeleted())

                // Additional entity checks
                , () -> assertNotNull(dbFileEntity.getKey())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNotNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
        );
    }

    @Test
    void fileDeleteToNew() throws Exception {

        Transaction fileDeleteTransaction = fileDeleteTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(fileDeleteTransaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(fileDeleteTransaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertTrue(dbFileEntity.isDeleted())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getKey())
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
        );
    }

    @Test
    void fileDeleteFailedTransaction() throws Exception {

        Transaction fileDeleteTransaction = fileDeleteTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(fileDeleteTransaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody,
                ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE);

        RecordFileLogger.storeRecord(fileDeleteTransaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertFalse(dbFileEntity.isDeleted())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getKey())
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
        );
    }

    @Test
    void fileSystemDeleteTransaction() throws Exception {

        Transaction systemDeleteTransaction = systemDeleteTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(systemDeleteTransaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(systemDeleteTransaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        com.hedera.mirror.importer.domain.Entities dbFileEntity = entityRepository
                .findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertTrue(dbFileEntity.isDeleted())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getKey())
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
        );
    }

    @Test
    void fileSystemUnDeleteTransaction() throws Exception {

        Transaction systemUndeleteTransaction = systemUnDeleteTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(systemUndeleteTransaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody);

        RecordFileLogger.storeRecord(systemUndeleteTransaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();
        Entities dbFileEntity = entityRepository.findById(dbTransaction.getEntityId()).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // receipt
                , () -> assertFile(record.getReceipt().getFileID(), dbFileEntity)

                // record transfer list
                , () -> assertRecordTransfers(record)

                // transaction body inputs
                , () -> assertFalse(dbFileEntity.isDeleted())

                // Additional entity checks
                , () -> assertNull(dbFileEntity.getKey())
                , () -> assertNull(dbFileEntity.getExpiryTimeSeconds())
                , () -> assertNull(dbFileEntity.getExpiryTimeNanos())
                , () -> assertNull(dbFileEntity.getExpiryTimeNs())
                , () -> assertNull(dbFileEntity.getAutoRenewPeriod())
                , () -> assertNull(dbFileEntity.getProxyAccountId())
        );
    }

    @Test
    void fileSystemDeleteInvalidTransaction() throws Exception {

        Transaction systemDeleteTransaction = systemDeleteTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(systemDeleteTransaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody,
                ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE);

        RecordFileLogger.storeRecord(systemDeleteTransaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // record transfer list
                , () -> assertRecordTransfers(record)
        );
    }

    @Test
    void fileSystemUnDeleteFailedTransaction() throws Exception {

        Transaction systemUndeleteTransaction = systemUnDeleteTransaction();
        TransactionBody transactionBody = TransactionBody.parseFrom(systemUndeleteTransaction.getBodyBytes());
        TransactionRecord record = transactionRecord(transactionBody,
                ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE);

        RecordFileLogger.storeRecord(systemUndeleteTransaction, record);

        RecordFileLogger.completeFile("", "");

        com.hedera.mirror.importer.domain.Transaction dbTransaction = transactionRepository
                .findById(Utility.timeStampInNanos(record.getConsensusTimestamp())).get();

        assertAll(
                // row counts
                () -> assertEquals(1, recordFileRepository.count())
                , () -> assertEquals(1, transactionRepository.count())
                , () -> assertEquals(5, entityRepository.count())
                , () -> assertEquals(0, contractResultRepository.count())
                , () -> assertEquals(3, cryptoTransferRepository.count())
                , () -> assertEquals(0, liveHashRepository.count())
                , () -> assertEquals(0, fileDataRepository.count())

                // transaction
                , () -> assertTransaction(transactionBody, dbTransaction)

                // record inputs
                , () -> assertRecord(record, dbTransaction)

                // record transfer list
                , () -> assertRecordTransfers(record)
        );
    }

    private TransactionRecord transactionRecord(TransactionBody transactionBody) {
        return transactionRecord(transactionBody, fileId);
    }

    private TransactionRecord transactionRecord(TransactionBody transactionBody, ResponseCodeEnum responseCode) {
        return transactionRecord(transactionBody, responseCode, fileId);
    }

    private TransactionRecord transactionRecord(TransactionBody transactionBody, FileID newFile) {
        return transactionRecord(transactionBody, ResponseCodeEnum.SUCCESS, newFile);
    }

    private TransactionRecord transactionRecord(TransactionBody transactionBody, ResponseCodeEnum responseCode,
                                                FileID newFile) {

        TransactionRecord.Builder record = TransactionRecord.newBuilder();

        // record
        Timestamp consensusTimeStamp = Utility.instantToTimestamp(Instant.now());
        long[] transferAccounts = {98, 2002, 3};
        long[] transferAmounts = {1000, -2000, 20};
        TransactionReceipt.Builder receipt = TransactionReceipt.newBuilder();

        // Build the record
        record.setConsensusTimestamp(consensusTimeStamp);
        record.setMemoBytes(ByteString.copyFromUtf8(transactionBody.getMemo()));
        receipt.setFileID(newFile);
        receipt.setStatus(responseCode);

        record.setReceipt(receipt.build());
        record.setTransactionFee(transactionBody.getTransactionFee());
        record.setTransactionHash(ByteString.copyFromUtf8("TransactionHash"));
        record.setTransactionID(transactionBody.getTransactionID());

        TransferList.Builder transferList = TransferList.newBuilder();

        for (int i = 0; i < transferAccounts.length; i++) {
            AccountAmount.Builder accountAmount = AccountAmount.newBuilder();
            accountAmount.setAccountID(AccountID.newBuilder().setShardNum(0).setRealmNum(0)
                    .setAccountNum(transferAccounts[i]));
            accountAmount.setAmount(transferAmounts[i]);
            transferList.addAccountAmounts(accountAmount);
        }

        record.setTransferList(transferList);

        return record.build();
    }

    private Transaction fileCreateTransaction() {
        return fileCreateTransaction(Timestamp.newBuilder()
                .setSeconds(1571487857L)
                .setNanos(181579000)
                .build());
    }

    private Transaction fileCreateTransaction(Timestamp expirationTime) {

        Transaction.Builder transaction = Transaction.newBuilder();
        FileCreateTransactionBody.Builder fileCreate = FileCreateTransactionBody.newBuilder();

        // file create
        final String fileData = "Hedera hashgraph is great!";
        final String key = "0a2212200aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92";

        // Build a transaction
        fileCreate.setContents(ByteString.copyFromUtf8(fileData));
        fileCreate.setExpirationTime(expirationTime);
        final KeyList.Builder keyList = KeyList.newBuilder();
        keyList.addKeys(keyFromString(key));
        fileCreate.setKeys(keyList);
        fileCreate.setNewRealmAdminKey(keyFromString(realmAdminKey));
        fileCreate.setRealmID(RealmID.newBuilder().setShardNum(0).setRealmNum(0).build());
        fileCreate.setShardID(ShardID.newBuilder().setShardNum(0));

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);

        // body transaction
        body.setFileCreate(fileCreate.build());
        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction fileAppendTransaction() {
        return fileAppendTransaction(fileId);
    }

    private Transaction fileAppendTransaction(FileID fileToAppendTo) {
        Transaction.Builder transaction = Transaction.newBuilder();
        FileAppendTransactionBody.Builder fileAppend = FileAppendTransactionBody.newBuilder();

        // file append
        String fileData = "Hedera hashgraph is even better!";

        // Build a transaction
        fileAppend.setContents(ByteString.copyFromUtf8(fileData));
        fileAppend.setFileID(fileToAppendTo);

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setFileAppend(fileAppend.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction fileUpdateAllTransaction() {
        return fileUpdateAllTransaction(fileId);
    }

    private Transaction fileUpdateAllTransaction(FileID fileToUpdate) {

        Transaction.Builder transaction = Transaction.newBuilder();
        FileUpdateTransactionBody.Builder fileUpdate = FileUpdateTransactionBody.newBuilder();
        String key = "0a2212200aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110fff";

        // file update
        String fileData = "Hedera hashgraph is even better!";

        // Build a transaction
        fileUpdate.setContents(ByteString.copyFromUtf8(fileData));
        fileUpdate.setFileID(fileToUpdate);
        fileUpdate.setExpirationTime(Utility.instantToTimestamp(Instant.now()));

        KeyList.Builder keyList = KeyList.newBuilder();
        keyList.addKeys(Key.newBuilder().setEd25519(ByteString.copyFromUtf8(key)).build());
        fileUpdate.setKeys(keyList);

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setFileUpdate(fileUpdate.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction fileUpdateContentsTransaction() {

        Transaction.Builder transaction = Transaction.newBuilder();
        FileUpdateTransactionBody.Builder fileUpdate = FileUpdateTransactionBody.newBuilder();

        // file update
        String fileData = "Hedera hashgraph is even better!";

        // Build a transaction
        fileUpdate.setContents(ByteString.copyFromUtf8(fileData));
        fileUpdate.setFileID(fileId);

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setFileUpdate(fileUpdate.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction fileUpdateExpiryTransaction() {

        Transaction.Builder transaction = Transaction.newBuilder();
        FileUpdateTransactionBody.Builder fileUpdate = FileUpdateTransactionBody.newBuilder();

        // Build a transaction
        fileUpdate.setFileID(fileId);
        fileUpdate.setExpirationTime(Utility.instantToTimestamp(Instant.now()));

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setFileUpdate(fileUpdate.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction fileUpdateKeysTransaction() {

        Transaction.Builder transaction = Transaction.newBuilder();
        FileUpdateTransactionBody.Builder fileUpdate = FileUpdateTransactionBody.newBuilder();
        String key = "0a2212200aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110fff";

        // Build a transaction
        fileUpdate.setFileID(fileId);

        KeyList.Builder keyList = KeyList.newBuilder();
        keyList.addKeys(Key.newBuilder().setEd25519(ByteString.copyFromUtf8(key)).build());
        fileUpdate.setKeys(keyList);

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setFileUpdate(fileUpdate.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction fileDeleteTransaction() {

        // transaction id
        Transaction.Builder transaction = Transaction.newBuilder();
        FileDeleteTransactionBody.Builder fileDelete = FileDeleteTransactionBody.newBuilder();

        // Build a transaction
        fileDelete.setFileID(fileId);

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setFileDelete(fileDelete.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction systemDeleteTransaction() {

        // transaction id
        Transaction.Builder transaction = Transaction.newBuilder();
        SystemDeleteTransactionBody.Builder systemDelete = SystemDeleteTransactionBody.newBuilder();

        // Build a transaction
        systemDelete.setFileID(fileId);
        systemDelete.setExpirationTime(TimestampSeconds.newBuilder().setSeconds(100000).build());

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setSystemDelete(systemDelete.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }

    private Transaction systemUnDeleteTransaction() {

        // transaction id
        Transaction.Builder transaction = Transaction.newBuilder();
        SystemUndeleteTransactionBody.Builder systemUnDelete = SystemUndeleteTransactionBody.newBuilder();

        // Build a transaction
        systemUnDelete.setFileID(fileId);

        // Transaction body
        TransactionBody.Builder body = defaultTransactionBodyBuilder(memo);
        // body transaction
        body.setSystemUndelete(systemUnDelete.build());

        transaction.setBodyBytes(body.build().toByteString());
        transaction.setSigMap(getSigMap());

        return transaction.build();
    }
}
