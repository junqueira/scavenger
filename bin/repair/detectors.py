#!/usr/bin/env python3

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from abc import ABCMeta, abstractmethod
from pyspark.sql import DataFrame, SparkSession

class ErrorDetector:

    __metaclass__ = ABCMeta

    def __init__(self, name):
        self.env = None
        self.spark = SparkSession.builder.getOrCreate()

        # JVM interfaces for Scavenger APIs
        self.api = self.spark.sparkContext._active_spark_context._jvm.ScavengerErrorDetectorApi

    def setup(self, env):
        self.env = env

    @abstractmethod
    def detect(self):
        raise NotImplementedError

class NullErrorDetector(ErrorDetector):

    def __init__(self):
        ErrorDetector.__init__(self, 'NullErrorDetector')

    def detect(self):
        jdf = self.api.detectNullCells('', self.env['input_table'], self.env['row_id'])
        return DataFrame(jdf, self.spark._wrapped)

class ConstraintErrorDetector(ErrorDetector):

    def __init__(self):
        ErrorDetector.__init__(self, 'ConstraintErrorDetector')

    def detect(self):
        jdf = self.api.detectErrorCellsFromConstraints(
            self.env['constraint_input_path'], '', self.env['input_table'], self.env['row_id'])
        return DataFrame(jdf, self.spark._wrapped)

class OutlierErrorDetector(ErrorDetector):

    def __init__(self):
        ErrorDetector.__init__(self, 'OutlierErrorDetector')

    def detect(self):
        jdf = self.api.detectErrorCellsFromOutliers(
            '', self.env['input_table'], self.env['row_id'], False)
        return DataFrame(jdf, self.spark._wrapped)

