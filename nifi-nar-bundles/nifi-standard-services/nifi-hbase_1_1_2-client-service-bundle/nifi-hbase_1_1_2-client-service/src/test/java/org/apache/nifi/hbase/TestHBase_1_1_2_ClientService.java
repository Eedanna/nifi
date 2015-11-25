/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.hbase;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.hbase.put.PutColumn;
import org.apache.nifi.hbase.put.PutFlowFile;
import org.apache.nifi.hbase.scan.Column;
import org.apache.nifi.hbase.scan.ResultCell;
import org.apache.nifi.hbase.scan.ResultHandler;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestHBase_1_1_2_ClientService {

    @Test
    public void testCustomValidate() throws InitializationException {
        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        final String tableName = "nifi";
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // no conf file or zk properties so should be invalid
        MockHBaseClientService service = new MockHBaseClientService(table);
        runner.addControllerService("hbaseClientService", service);
        runner.enableControllerService(service);

        runner.assertNotValid(service);
        runner.removeControllerService(service);

        // conf file with no zk properties should be valid
        service = new MockHBaseClientService(table);
        runner.addControllerService("hbaseClientService", service);
        runner.setProperty(service, HBase_1_1_2_ClientService.HADOOP_CONF_FILES, "src/test/resources/core-site.xml");
        runner.enableControllerService(service);

        runner.assertValid(service);
        runner.removeControllerService(service);

        // only quorum and no conf file should be invalid
        service = new MockHBaseClientService(table);
        runner.addControllerService("hbaseClientService", service);
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_QUORUM, "localhost");
        runner.enableControllerService(service);

        runner.assertNotValid(service);
        runner.removeControllerService(service);

        // quorum and port, no znode, no conf file, should be invalid
        service = new MockHBaseClientService(table);
        runner.addControllerService("hbaseClientService", service);
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_QUORUM, "localhost");
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_CLIENT_PORT, "2181");
        runner.enableControllerService(service);

        runner.assertNotValid(service);
        runner.removeControllerService(service);

        // quorum, port, and znode, no conf file, should be valid
        service = new MockHBaseClientService(table);
        runner.addControllerService("hbaseClientService", service);
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_QUORUM, "localhost");
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_CLIENT_PORT, "2181");
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_ZNODE_PARENT, "/hbase");
        runner.enableControllerService(service);

        runner.assertValid(service);
        runner.removeControllerService(service);

        // quorum and port with conf file should be valid
        service = new MockHBaseClientService(table);
        runner.addControllerService("hbaseClientService", service);
        runner.setProperty(service, HBase_1_1_2_ClientService.HADOOP_CONF_FILES, "src/test/resources/core-site.xml");
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_QUORUM, "localhost");
        runner.setProperty(service, HBase_1_1_2_ClientService.ZOOKEEPER_CLIENT_PORT, "2181");
        runner.enableControllerService(service);

        runner.assertValid(service);
        runner.removeControllerService(service);
    }

    @Test
    public void testSinglePut() throws InitializationException, IOException {
        final String tableName = "nifi";
        final String row = "row1";
        final String columnFamily = "family1";
        final String columnQualifier = "qualifier1";
        final String content = "content1";

        final Collection<PutColumn> columns = Collections.singletonList(new PutColumn(columnFamily, columnQualifier,
                content.getBytes(StandardCharsets.UTF_8)));
        final PutFlowFile putFlowFile = new PutFlowFile(tableName, row, columns, null);

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final HBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        // try to put a single cell
        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
                .asControllerService(HBaseClientService.class);

        hBaseClientService.put(tableName, Arrays.asList(putFlowFile));

        // verify only one call to put was made
        ArgumentCaptor<List> capture = ArgumentCaptor.forClass(List.class);
        verify(table, times(1)).put(capture.capture());

        // verify only one put was in the list of puts
        final List<Put> puts = capture.getValue();
        assertEquals(1, puts.size());
        verifyPut(row, columnFamily, columnQualifier, content, puts.get(0));
    }

    @Test
    public void testMultiplePutsSameRow() throws IOException, InitializationException {
        final String tableName = "nifi";
        final String row = "row1";
        final String columnFamily = "family1";
        final String columnQualifier = "qualifier1";
        final String content1 = "content1";
        final String content2 = "content2";

        final Collection<PutColumn> columns1 = Collections.singletonList(new PutColumn(columnFamily, columnQualifier,
                content1.getBytes(StandardCharsets.UTF_8)));
        final PutFlowFile putFlowFile1 = new PutFlowFile(tableName, row, columns1, null);

        final Collection<PutColumn> columns2 = Collections.singletonList(new PutColumn(columnFamily, columnQualifier,
                content2.getBytes(StandardCharsets.UTF_8)));
        final PutFlowFile putFlowFile2 = new PutFlowFile(tableName, row, columns2, null);

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final HBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        // try to put a multiple cells for the same row
        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
                .asControllerService(HBaseClientService.class);

        hBaseClientService.put(tableName, Arrays.asList(putFlowFile1, putFlowFile2));

        // verify put was only called once
        ArgumentCaptor<List> capture = ArgumentCaptor.forClass(List.class);
        verify(table, times(1)).put(capture.capture());

        // verify there was only one put in the list of puts
        final List<Put> puts = capture.getValue();
        assertEquals(1, puts.size());

        // verify two cells were added to this one put operation
        final NavigableMap<byte[], List<Cell>> familyCells = puts.get(0).getFamilyCellMap();
        Map.Entry<byte[], List<Cell>> entry = familyCells.firstEntry();
        assertEquals(2, entry.getValue().size());
    }

    @Test
    public void testMultiplePutsDifferentRow() throws IOException, InitializationException {
        final String tableName = "nifi";
        final String row1 = "row1";
        final String row2 = "row2";
        final String columnFamily = "family1";
        final String columnQualifier = "qualifier1";
        final String content1 = "content1";
        final String content2 = "content2";

        final Collection<PutColumn> columns1 = Collections.singletonList(new PutColumn(columnFamily, columnQualifier,
                content1.getBytes(StandardCharsets.UTF_8)));
        final PutFlowFile putFlowFile1 = new PutFlowFile(tableName, row1, columns1, null);

        final Collection<PutColumn> columns2 = Collections.singletonList(new PutColumn(columnFamily, columnQualifier,
                content2.getBytes(StandardCharsets.UTF_8)));
        final PutFlowFile putFlowFile2 = new PutFlowFile(tableName, row2, columns2, null);

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final HBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        // try to put a multiple cells with different rows
        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
                .asControllerService(HBaseClientService.class);

        hBaseClientService.put(tableName, Arrays.asList(putFlowFile1, putFlowFile2));

        // verify put was only called once
        ArgumentCaptor<List> capture = ArgumentCaptor.forClass(List.class);
        verify(table, times(1)).put(capture.capture());

        // verify there were two puts in the list
        final List<Put> puts = capture.getValue();
        assertEquals(2, puts.size());
    }

    @Test
    public void testScan() throws InitializationException, IOException {
        final String tableName = "nifi";
        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        // stage some results in the mock service...
        final long now = System.currentTimeMillis();

        final Map<String, String> cells = new HashMap<>();
        cells.put("greeting", "hello");
        cells.put("name", "nifi");

        service.addResult("row0", cells, now - 2);
        service.addResult("row1", cells, now - 1);
        service.addResult("row2", cells, now - 1);
        service.addResult("row3", cells, now);

        // perform a scan and verify the four rows were returned
        final CollectingResultHandler handler = new CollectingResultHandler();
        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
                .asControllerService(HBaseClientService.class);

        hBaseClientService.scan(tableName, new ArrayList<Column>(), null, now, handler);
        assertEquals(4, handler.results.size());

        // get row0 using the row id and verify it has 2 cells
        final ResultCell[] results = handler.results.get("row0");
        assertNotNull(results);
        assertEquals(2, results.length);

        verifyResultCell(results[0], "nifi", "greeting", "hello");
        verifyResultCell(results[1], "nifi", "name", "nifi");
    }

    @Test
    public void testScanWithValidFilter() throws InitializationException, IOException {
        final String tableName = "nifi";
        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        // perform a scan and verify the four rows were returned
        final CollectingResultHandler handler = new CollectingResultHandler();
        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
                .asControllerService(HBaseClientService.class);

        // make sure we parse the filter expression without throwing an exception
        final String filter = "PrefixFilter ('Row') AND PageFilter (1) AND FirstKeyOnlyFilter ()";
        hBaseClientService.scan(tableName, new ArrayList<Column>(), filter, System.currentTimeMillis(), handler);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testScanWithInvalidFilter() throws InitializationException, IOException {
        final String tableName = "nifi";
        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        // perform a scan and verify the four rows were returned
        final CollectingResultHandler handler = new CollectingResultHandler();
        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
                .asControllerService(HBaseClientService.class);

        // this should throw IllegalArgumentException
        final String filter = "this is not a filter";
        hBaseClientService.scan(tableName, new ArrayList<Column>(), filter, System.currentTimeMillis(), handler);
    }

    private MockHBaseClientService configureHBaseClientService(final TestRunner runner, final Table table) throws InitializationException {
        final MockHBaseClientService service = new MockHBaseClientService(table);
        runner.addControllerService("hbaseClient", service);
        runner.setProperty(service, HBase_1_1_2_ClientService.HADOOP_CONF_FILES, "src/test/resources/core-site.xml");
        runner.enableControllerService(service);
        runner.setProperty(TestProcessor.HBASE_CLIENT_SERVICE, "hbaseClient");
        return service;
    }

    private void verifyResultCell(final ResultCell result, final String cf, final String cq, final String val) {
        final String colFamily = new String(result.getFamilyArray(), result.getFamilyOffset(), result.getFamilyLength());
        assertEquals(cf, colFamily);

        final String colQualifier = new String(result.getQualifierArray(), result.getQualifierOffset(), result.getQualifierLength());
        assertEquals(cq, colQualifier);

        final String value = new String(result.getValueArray(), result.getValueOffset(), result.getValueLength());
        assertEquals(val, value);
    }

    private void verifyPut(String row, String columnFamily, String columnQualifier, String content, Put put) {
        assertEquals(row, new String(put.getRow()));

        NavigableMap<byte [], List<Cell>> familyCells = put.getFamilyCellMap();
        assertEquals(1, familyCells.size());

        Map.Entry<byte[], List<Cell>> entry = familyCells.firstEntry();
        assertEquals(columnFamily, new String(entry.getKey()));
        assertEquals(1, entry.getValue().size());

        Cell cell = entry.getValue().get(0);
        assertEquals(columnQualifier, new String(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength()));
        assertEquals(content, new String(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength()));
    }

    // Override methods to create a mock service that can return staged data
    private class MockHBaseClientService extends HBase_1_1_2_ClientService {

        private Table table;
        private List<Result> results = new ArrayList<>();

        public MockHBaseClientService(final Table table) {
            this.table = table;
        }

        public void addResult(final String rowKey, final Map<String, String> cells, final long timestamp) {
            final byte[] rowArray = rowKey.getBytes(StandardCharsets.UTF_8);

            final Cell[] cellArray = new Cell[cells.size()];
            int i = 0;
            for (final Map.Entry<String, String> cellEntry : cells.entrySet()) {
                final Cell cell = Mockito.mock(Cell.class);
                when(cell.getRowArray()).thenReturn(rowArray);
                when(cell.getRowOffset()).thenReturn(0);
                when(cell.getRowLength()).thenReturn((short) rowArray.length);

                final String cellValue = cellEntry.getValue();
                final byte[] valueArray = cellValue.getBytes(StandardCharsets.UTF_8);
                when(cell.getValueArray()).thenReturn(valueArray);
                when(cell.getValueOffset()).thenReturn(0);
                when(cell.getValueLength()).thenReturn(valueArray.length);

                final byte[] familyArray = "nifi".getBytes(StandardCharsets.UTF_8);
                when(cell.getFamilyArray()).thenReturn(familyArray);
                when(cell.getFamilyOffset()).thenReturn(0);
                when(cell.getFamilyLength()).thenReturn((byte) familyArray.length);

                final String qualifier = cellEntry.getKey();
                final byte[] qualifierArray = qualifier.getBytes(StandardCharsets.UTF_8);
                when(cell.getQualifierArray()).thenReturn(qualifierArray);
                when(cell.getQualifierOffset()).thenReturn(0);
                when(cell.getQualifierLength()).thenReturn(qualifierArray.length);

                when(cell.getTimestamp()).thenReturn(timestamp);

                cellArray[i++] = cell;
            }

            final Result result = Mockito.mock(Result.class);
            when(result.getRow()).thenReturn(rowArray);
            when(result.rawCells()).thenReturn(cellArray);
            results.add(result);
        }

        @Override
        protected ResultScanner getResults(Table table, Collection<Column> columns, Filter filter, long minTime) throws IOException {
            final ResultScanner scanner = Mockito.mock(ResultScanner.class);
            Mockito.when(scanner.iterator()).thenReturn(results.iterator());
            return scanner;
        }

        @Override
        protected Connection createConnection(ConfigurationContext context) throws IOException {
            Connection connection = Mockito.mock(Connection.class);
            Mockito.when(connection.getTable(table.getName())).thenReturn(table);
            return connection;
        }
    }

    // handler that saves results for verification
    private static final class CollectingResultHandler implements ResultHandler {

        Map<String,ResultCell[]> results = new LinkedHashMap<>();

        @Override
        public void handle(byte[] row, ResultCell[] resultCells) {
            final String rowStr = new String(row, StandardCharsets.UTF_8);
            results.put(rowStr, resultCells);
        }
    }

}