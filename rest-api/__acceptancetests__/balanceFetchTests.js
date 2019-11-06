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
const balancesPath= '/balances';
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

const getBalances = async function(pathandquery) {
    try {
        const json = await acctestutils.getAPIResponse(pathandquery);
        return json.balances;
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
    ['account', 'balance'].forEach((field) => {
        check = check && entry.hasOwnProperty(field);
    });

    return (check);
}

const getBalancesWithAccountCheck = async function() {
    var currentTestResult = acctestutils.cloneObject(testResult);
    currentTestResult.at = Date.now();
    
    let url = acctestutils.getUrl(balancesPath);
    currentTestResult.url = url;
    let balances = await getBalances(url); 

    if (balances.length !== maxLimit) {
        var message = `balances.length of ${balances.length} is less than limit ${maxLimit}`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }

    let mandatoryParamCheck = checkMandatoryParams(balances[0]);
    if (mandatoryParamCheck == false) {
        var message = `balance object is missing some mandatory fields`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }

    currentTestResult.result = 'passed';
    currentTestResult.message = `Successfully called balances and performed account check`

    addTestResult(currentTestResult, true);
}

const getBalancesWithTimeAndLimitParams = async function (json) {
    var currentTestResult = acctestutils.cloneObject(testResult);
    currentTestResult.at = Date.now();

    let url = acctestutils.getUrl(`${balancesPath}?limit=1`);
    currentTestResult.url = url;
    let balancesResponse = await acctestutils.getAPIResponse(url);
    let balances = balancesResponse.balances

    if (balances.length !== 1) {
        var message = `balances.length of ${balances.length} was expected to be 1`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }

    let plusOne = math.add(math.bignumber(balancesResponse.timestamp), math.bignumber(1));
    let minusOne = math.subtract(math.bignumber(balancesResponse.timestamp), math.bignumber(1));
    let paq = `${balancesPath}?timestamp=gt:${minusOne.toString()}` +
                `&timestamp=lt:${plusOne.toString()}&limit=1`;

    url = acctestutils.getUrl(paq);
    currentTestResult.url = url;
    balances = await getBalances(url);

    if (balances.length !== 1) {
        var message = `balances.length of ${balances.length} was expected to be 1`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return;
    }

    currentTestResult.result = 'passed';
    currentTestResult.message = `Successfully called balances with time and limit params`

    addTestResult(currentTestResult, true);
}

const getSingleBalanceById = async function() {
    var currentTestResult = acctestutils.cloneObject(testResult);
    currentTestResult.at = Date.now();
    
    let url = acctestutils.getUrl(`${balancesPath}?limit=10`);
    currentTestResult.url = url;
    let balances = await getBalances(url); 

    if (balances.length !== 10) {
        var message = `balances.length of ${balances.length} is less than limit ${maxLimit}`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return currentTestResult;
    }

    let mandatoryParamCheck = checkMandatoryParams(balances[0]);
    if (mandatoryParamCheck == false) {
        var message = `account object is missing some mandatory fields`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return currentTestResult;
    }
    
    var highestAcc = 0
    for (let acc of balances) {
        var accnum = acctestutils.toAccNum(acc.account)
        if (accnum > highestAcc) {
            highestAcc = accnum;
        }
    }

    url = acctestutils.getUrl(`${balancesPath}?account.id=${acctestutils.fromAccNum(highestAcc)}`);
    currentTestResult.url = url;
    let singleBalance = await getBalances(url); 

    let check = false;
    if (singleBalance[0].account === acctestutils.fromAccNum(highestAcc)) {
        check = true;
    }

    if (check == false) {
        var message = `Highest acc check was not found`;
        currentTestResult.failureMessages.push(message);
        addTestResult(currentTestResult, false);
        return
    }

    currentTestResult.result = 'passed';
    currentTestResult.message = `Successfully called balances and performed account check`

    addTestResult(currentTestResult, true);
}

async function runTests() {
    var tests = [];
    tests.push(getBalancesWithAccountCheck());
    tests.push(getBalancesWithTimeAndLimitParams());
    tests.push(getSingleBalanceById());

    await Promise.all(tests);

    if (classResults.numPassedTests == classResults.testResults.length) {
        classResults.success = true
    }
}

const runBalnceTests = function() {
    classResults.startTime = Date.now();

    return runTests().then(() => {
        return classResults;
    });
}

module.exports = {
    runBalnceTests: runBalnceTests
}