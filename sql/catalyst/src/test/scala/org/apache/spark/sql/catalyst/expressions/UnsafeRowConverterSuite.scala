/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.sql.{Date, Timestamp}
import java.util.Arrays

import org.scalatest.Matchers

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.PlatformDependent
import org.apache.spark.unsafe.array.ByteArrayMethods

class UnsafeRowConverterSuite extends SparkFunSuite with Matchers {

  test("basic conversion with only primitive types") {
    val fieldTypes: Array[DataType] = Array(LongType, LongType, IntegerType)
    val converter = new UnsafeRowConverter(fieldTypes)

    val row = new SpecificMutableRow(fieldTypes)
    row.setLong(0, 0)
    row.setLong(1, 1)
    row.setInt(2, 2)

    val sizeRequired: Int = converter.getSizeRequirement(row)
    sizeRequired should be (8 + (3 * 8))
    val buffer: Array[Long] = new Array[Long](sizeRequired / 8)
    val numBytesWritten = converter.writeRow(row, buffer, PlatformDependent.LONG_ARRAY_OFFSET)
    numBytesWritten should be (sizeRequired)

    val unsafeRow = new UnsafeRow()
    unsafeRow.pointTo(buffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    unsafeRow.getLong(0) should be (0)
    unsafeRow.getLong(1) should be (1)
    unsafeRow.getInt(2) should be (2)
  }

  test("basic conversion with primitive, string and binary types") {
    val fieldTypes: Array[DataType] = Array(LongType, StringType, BinaryType)
    val converter = new UnsafeRowConverter(fieldTypes)

    val row = new SpecificMutableRow(fieldTypes)
    row.setLong(0, 0)
    row.setString(1, "Hello")
    row.update(2, "World".getBytes)

    val sizeRequired: Int = converter.getSizeRequirement(row)
    sizeRequired should be (8 + (8 * 3) +
      ByteArrayMethods.roundNumberOfBytesToNearestWord("Hello".getBytes.length) +
      ByteArrayMethods.roundNumberOfBytesToNearestWord("World".getBytes.length))
    val buffer: Array[Long] = new Array[Long](sizeRequired / 8)
    val numBytesWritten = converter.writeRow(row, buffer, PlatformDependent.LONG_ARRAY_OFFSET)
    numBytesWritten should be (sizeRequired)

    val unsafeRow = new UnsafeRow()
    unsafeRow.pointTo(buffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    unsafeRow.getLong(0) should be (0)
    unsafeRow.getString(1) should be ("Hello")
    unsafeRow.getBinary(2) should be ("World".getBytes)
  }

  test("basic conversion with primitive, string, date and timestamp types") {
    val fieldTypes: Array[DataType] = Array(LongType, StringType, DateType, TimestampType)
    val converter = new UnsafeRowConverter(fieldTypes)

    val row = new SpecificMutableRow(fieldTypes)
    row.setLong(0, 0)
    row.setString(1, "Hello")
    row.update(2, DateTimeUtils.fromJavaDate(Date.valueOf("1970-01-01")))
    row.update(3, DateTimeUtils.fromJavaTimestamp(Timestamp.valueOf("2015-05-08 08:10:25")))

    val sizeRequired: Int = converter.getSizeRequirement(row)
    sizeRequired should be (8 + (8 * 4) +
      ByteArrayMethods.roundNumberOfBytesToNearestWord("Hello".getBytes.length))
    val buffer: Array[Long] = new Array[Long](sizeRequired / 8)
    val numBytesWritten = converter.writeRow(row, buffer, PlatformDependent.LONG_ARRAY_OFFSET)
    numBytesWritten should be (sizeRequired)

    val unsafeRow = new UnsafeRow()
    unsafeRow.pointTo(buffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    unsafeRow.getLong(0) should be (0)
    unsafeRow.getString(1) should be ("Hello")
    // Date is represented as Int in unsafeRow
    DateTimeUtils.toJavaDate(unsafeRow.getInt(2)) should be (Date.valueOf("1970-01-01"))
    // Timestamp is represented as Long in unsafeRow
    DateTimeUtils.toJavaTimestamp(unsafeRow.getLong(3)) should be
      (Timestamp.valueOf("2015-05-08 08:10:25"))
  }

  test("null handling") {
    val fieldTypes: Array[DataType] = Array(
      NullType,
      BooleanType,
      ByteType,
      ShortType,
      IntegerType,
      LongType,
      FloatType,
      DoubleType)
    val converter = new UnsafeRowConverter(fieldTypes)

    val rowWithAllNullColumns: InternalRow = {
      val r = new SpecificMutableRow(fieldTypes)
      for (i <- 0 to fieldTypes.length - 1) {
        r.setNullAt(i)
      }
      r
    }

    val sizeRequired: Int = converter.getSizeRequirement(rowWithAllNullColumns)
    val createdFromNullBuffer: Array[Long] = new Array[Long](sizeRequired / 8)
    val numBytesWritten = converter.writeRow(
      rowWithAllNullColumns, createdFromNullBuffer, PlatformDependent.LONG_ARRAY_OFFSET)
    numBytesWritten should be (sizeRequired)

    val createdFromNull = new UnsafeRow()
    createdFromNull.pointTo(
      createdFromNullBuffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    for (i <- 0 to fieldTypes.length - 1) {
      assert(createdFromNull.isNullAt(i))
    }
    createdFromNull.getBoolean(1) should be (false)
    createdFromNull.getByte(2) should be (0)
    createdFromNull.getShort(3) should be (0)
    createdFromNull.getInt(4) should be (0)
    createdFromNull.getLong(5) should be (0)
    assert(java.lang.Float.isNaN(createdFromNull.getFloat(6)))
    assert(java.lang.Double.isNaN(createdFromNull.getFloat(7)))

    // If we have an UnsafeRow with columns that are initially non-null and we null out those
    // columns, then the serialized row representation should be identical to what we would get by
    // creating an entirely null row via the converter
    val rowWithNoNullColumns: InternalRow = {
      val r = new SpecificMutableRow(fieldTypes)
      r.setNullAt(0)
      r.setBoolean(1, false)
      r.setByte(2, 20)
      r.setShort(3, 30)
      r.setInt(4, 400)
      r.setLong(5, 500)
      r.setFloat(6, 600)
      r.setDouble(7, 700)
      r
    }
    val setToNullAfterCreationBuffer: Array[Long] = new Array[Long](sizeRequired / 8)
    converter.writeRow(
      rowWithNoNullColumns, setToNullAfterCreationBuffer, PlatformDependent.LONG_ARRAY_OFFSET)
    val setToNullAfterCreation = new UnsafeRow()
    setToNullAfterCreation.pointTo(
      setToNullAfterCreationBuffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)

    setToNullAfterCreation.isNullAt(0) should be (rowWithNoNullColumns.isNullAt(0))
    setToNullAfterCreation.getBoolean(1) should be (rowWithNoNullColumns.getBoolean(1))
    setToNullAfterCreation.getByte(2) should be (rowWithNoNullColumns.getByte(2))
    setToNullAfterCreation.getShort(3) should be (rowWithNoNullColumns.getShort(3))
    setToNullAfterCreation.getInt(4) should be (rowWithNoNullColumns.getInt(4))
    setToNullAfterCreation.getLong(5) should be (rowWithNoNullColumns.getLong(5))
    setToNullAfterCreation.getFloat(6) should be (rowWithNoNullColumns.getFloat(6))
    setToNullAfterCreation.getDouble(7) should be (rowWithNoNullColumns.getDouble(7))

    for (i <- 0 to fieldTypes.length - 1) {
      setToNullAfterCreation.setNullAt(i)
    }
    assert(Arrays.equals(createdFromNullBuffer, setToNullAfterCreationBuffer))
  }

}
