#!/usr/bin/env python
# **********************************************************************
#
# Copyright (c) 2003-2016 ZeroC, Inc. All rights reserved.
#
# This copy of Ice is licensed to you under the terms described in the
# ICE_LICENSE file included in this distribution.
#
# **********************************************************************

import os, sys, traceback

import Ice
Ice.loadSlice('--all -I. ClientPrivate.ice')
import AllTests

def run(args, communicator):
    initial = AllTests.allTests(communicator)
    initial.shutdown()
    return True

try:
    with Ice.initialize(sys.argv) as communicator:
         status = run(sys.argv, communicator)
except:
    traceback.print_exc()
    status = False

sys.exit(not status)
