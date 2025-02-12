package com.hedera.mirror.importer.repository;

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

import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

import com.hedera.mirror.importer.domain.Entities;
import com.hedera.mirror.importer.domain.LiveHash;
import com.hedera.mirror.importer.domain.RecordFile;
import com.hedera.mirror.importer.domain.Transaction;

@Sql(executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD, scripts = "classpath:db/scripts/cleanup.sql")
// Class manually commits so have to manually cleanup tables
public class LiveHashRepositoryTest extends AbstractRepositoryTest {

    @Test
    void insert() {
        RecordFile recordfile = insertRecordFile();
        Entities entity = insertAccountEntity();
        Transaction transaction = insertTransaction(recordfile.getId(), entity.getId(), "CRYPTOADDCLAIM");

        LiveHash liveHash = new LiveHash();
        liveHash.setConsensusTimestamp(transaction.getConsensusNs());
        liveHash.setLivehash("some live hash".getBytes());
        liveHash = liveHashRepository.save(liveHash);

        assertThat(liveHashRepository.findById(transaction.getConsensusNs()).get())
                .isNotNull()
                .isEqualTo(liveHash);
    }
}
