// **********************************************************************
//
// Copyright (c) 2003-2006 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

public class Server
{
    private static int run(string[] args, Ice.Communicator communicator)
    {
	communicator.getProperties().setProperty("TestAdapter.Endpoints", "default -p 12010 -t 2000:udp");
	Ice.ObjectAdapter adapter = communicator.createObjectAdapter("TestAdapter");
	Ice.Identity id = communicator.stringToIdentity("communicator");
	adapter.add(new RemoteCommunicatorI(), id);
	adapter.activate();

	communicator.waitForShutdown();
	return 0;
    }
    
    public static void Main(string[] args)
    {
        int status = 0;
        Ice.Communicator communicator = null;
        
        try
        {
	    Ice.InitializationData initData = new Ice.InitializationData();
            initData.properties = Ice.Util.createProperties(ref args);
            initData.properties.setProperty("Ice.ServerIdleTime", "30");
            communicator = Ice.Util.initialize(ref args, initData);
            status = run(args, communicator);
        }
        catch(Ice.LocalException ex)
        {
            System.Console.Error.WriteLine(ex);
            status = 1;
        }
        
        if(communicator != null)
        {
            try
            {
                communicator.destroy();
            }
            catch(Ice.LocalException ex)
            {
                System.Console.Error.WriteLine(ex);
                status = 1;
            }
        }
        
        System.Environment.Exit(status);
    }
}
