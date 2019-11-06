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

'use strict';

const acctestutils = require('./acceptancetest_utils.js');
const config = require('../config.js');
const math = require('mathjs');
const accountsPath= '/accounts';
const maxLimit = config.api.maxLimit;

let classResults = {
    startTime: null,
    testResults: [],
    numPassedTests: 0,
    numFailedTests: 0,
    success: false,
    message: ''
}

let testResult = {
    at: '',
    result: 'failed',
    url: '',
    message: '',
    failureMessages: []
}

const getAccounts = async function(pathandquery) {
    try {
        const json = await acctestutils.getAPIResponse(pathandquery);
        return json.accounts;
    } catch (error) {
        console.log(error);
    }
}

const addTestResult = function(res, passed) {
    classResults.testResults.push(res);
    passed ? classResults.numPassedTests++ : classResults.numFailedTests++;
}

const checkMandatoryParams = function (entry) {
    let check = true;
    ['balance', 'account', 'expiry_timestamp', 'auto_renew_period',
        'key', 'deleted'
    ].forEach((field) => {
        check = check && entry.hasOwnProperty(field);
    });

    ['timestamp', 'balance'].forEach((field) => {
        check = check && entry.hasOwnProperty('balance') && entry.balance.hasOwnProperty(field);
    });

    return (check);
}

const getAccountssWithAccountCheck = async function() {
    var currentTestResult = acctestutils.cloneObject(testResult);
    currentTestResult.at = Date.now();
    
    let url = acctestutils.getUrl(accountsPath);
    currentTestResult.url = url;
    let accounts = await getAccounts(url); 

    if (accounts.length !== maxLimit) {
        var message = `accounts.length of ${accounts.length} is less than limit ${maxLimit}`;
        currentTestResult.failureMessages.push(message)
        return currentTestResult;
    }

    let mandatoryParamCheck = checkMandatoryParams(accounts[0]);
    if (mandatoryParamCheck == false) {
        var message = `account object is missing some mandatory fields`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }
    
    var highestAcc = 0
    for (let acc of accounts) {
        var accnum = acctestutils.toAccNum(acc.account)
        if (accnum > highestAcc) {
            highestAcc = accnum;
        }
    }

    url = acctestutils.getUrl(`${accountsPath}?account.id=${highestAcc}&type=credit&limit=1`);
    currentTestResult.url = url;

    let singleAccount = await getAccounts(url); 

    if (singleAccount.length !== 1) {
        var message = `singleAccount.length of ${singleAccount.length} was expected to be 1`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return
    }

    let check = false;
    if (singleAccount[0].account === acctestutils.fromAccNum(highestAcc)) {
        check = true;
    }

    if (check == false) {
        var message = `Highest acc check was not found`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return
    }

    currentTestResult.result = 'passed';
    currentTestResult.message = `Successfully called accounts and performed account check`

    addTestResult(currentTestResult, true);
}

const getAccountsWithTimeAndLimitParams = async function (json) {
    var currentTestResult = acctestutils.cloneObject(testResult);
    currentTestResult.at = Date.now();

    let url = acctestutils.getUrl(`${accountsPath}?limit=1`);
    currentTestResult.url = url;
    let accounts = await getAccounts(url); 

    if (accounts.length !== 1) {
        var message = `accounts.length of ${accounts.length} was expected to be 1`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }

    let plusOne = math.add(math.bignumber(accounts[0].balance.timestamp), math.bignumber(1));
    let minusOne = math.subtract(math.bignumber(accounts[0].balance.timestamp), math.bignumber(1));
    let paq = `${accountsPath}?timestamp=gt:${minusOne.toString()}` +
                `&timestamp=lt:${plusOne.toString()}&limit=1`;

    url = acctestutils.getUrl(paq);
    currentTestResult.url = url;
    accounts = await getAccounts(url);

    if (accounts.length !== 1) {
        var message = `accounts.length of ${accounts.length} was expected to be 1`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }

    currentTestResult.result = 'passed';
    currentTestResult.message = `Successfully called accounts with time and limit params`

    addTestResult(currentTestResult, true);
}

const getSingleAccount = async function() {
    var currentTestResult = acctestutils.cloneObject(testResult);
    currentTestResult.at = Date.now();
    
    let url = acctestutils.getUrl(`${accountsPath}`);
    currentTestResult.url = url;
    let accounts = await getAccounts(url);  

    if (accounts.length !== maxLimit) {
        var message = `accounts.length of ${accounts.length} is less than limit ${maxLimit}`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }

    let mandatoryParamCheck = checkMandatoryParams(accounts[0]);
    if (mandatoryParamCheck == false) {
        var message = `account object is missing some mandatory fields`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }
    
    var highestAcc = 0
    for (let acc of accounts) {
        var accnum = acctestutils.toAccNum(acc.account)
        if (accnum > highestAcc) {
            highestAcc = accnum;
        }
    }

    url = acctestutils.getUrl(`${accountsPath}/${acctestutils.fromAccNum(highestAcc)}`);
    currentTestResult.url = url;

    let singleAccount = await acctestutils.getAPIResponse(url); 

    let check = false;
    if (singleAccount.account === acctestutils.fromAccNum(highestAcc)) {
        check = true;
    }

    if (check == false) {
        var message = `Highest acc check was not found`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return
    }

    currentTestResult.result = 'passed';
    currentTestResult.message = `Successfully called accounts and performed account check`

    addTestResult(currentTestResult, true);
}


async function runTests() {
    var tests = [];
    tests.push(getAccountssWithAccountCheck());
    tests.push(getAccountsWithTimeAndLimitParams());
    tests.push(getSingleAccount());

    await Promise.all(tests);

    if (classResults.numPassedTests == classResults.testResults.length) {
        classResults.success = true
    }
}

const runAccountTests = function() {
    classResults.startTime = Date.now();

    return runTests().then(() => {
        return classResults;
    });
}

module.exports = {
    runAccountTests: runAccountTests
}