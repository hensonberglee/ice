// **********************************************************************
//
// Copyright (c) 2003-2016 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

package com.zeroc.Glacier2;

import com.zeroc.Ice.ACMHeartbeat;
import com.zeroc.Ice.InitializationData;

/**
 * A helper class for using Glacier2 with GUI applications.
 */
public class SessionHelper
{
    /**
     * Creates a Glacier2 session.
     *
     * @param callback The callback for notifications about session establishment.
     * @param initData The InitializationData structure used to initialize the communicator.
     * @param finderStr The stringified Ice.RouterFinder proxy.
     * @param useCallbacks True if the session should create an object adapter for receiving callbacks.
     */
    public SessionHelper(SessionCallback callback, InitializationData initData, String finderStr,
                         boolean useCallbacks)
    {
        _callback = callback;
        _initData = initData;
        _finderStr = finderStr;
        _useCallbacks = useCallbacks;
    }

    /**
     * Destroys the Glacier2 session.
     *
     * Once the session has been destroyed, {@link SessionCallback#disconnected} is called on
     * the associated callback object.
     */
    public void destroy()
    {
        synchronized(this)
        {
            if(_destroy)
            {
                return;
            }
            _destroy = true;
            if(!_connected)
            {
                //
                // In this case a connecting session is being destroyed.
                // We destroy the communicator to trigger the immediate
                // failure of the connection establishment.
                //
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        destroyCommunicator();
                    }
                }).start();
                return;
            }
            _session = null;
            _connected = false;

            try
            {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);
            }
            catch(IllegalStateException ex)
            {
                // Ignore
            }
            catch(SecurityException ex)
            {
                // Ignore
            }
        }

        //
        // Run destroyInternal in a thread because it makes remote invocations.
        //
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                destroyInternal();
            }
        }).start();
    }

    /**
     * Returns the session's communicator object.
     * @return The communicator.
     */
    synchronized public com.zeroc.Ice.Communicator communicator()
    {
        return _communicator;
    }

    /**
     * Returns the category to be used in the identities of all of the client's
     * callback objects. Clients must use this category for the router to
     * forward callback requests to the intended client.
     * @return The category.
     * @throws SessionNotExistException No session exists.
     **/
    synchronized public String categoryForClient()
        throws SessionNotExistException
    {
        if(_router == null)
        {
            throw new SessionNotExistException();
        }

        return _category;
    }

    /**
     * Adds a servant to the callback object adapter's Active Servant Map with a UUID.
     * @param servant The servant to add.
     * @return The proxy for the servant.
     * @throws SessionNotExistException No session exists.
     **/
    synchronized public com.zeroc.Ice.ObjectPrx addWithUUID(com.zeroc.Ice.Object servant)
        throws SessionNotExistException
    {
        if(_router == null)
        {
            throw new SessionNotExistException();
        }
        return internalObjectAdapter().add(servant, new com.zeroc.Ice.Identity(java.util.UUID.randomUUID().toString(),
                                                                               _category));
    }

    /**
     * Returns the Glacier2 session proxy. If the session hasn't been established yet,
     * or the session has already been destroyed, throws SessionNotExistException.
     * @return The session proxy, or throws SessionNotExistException if no session exists.
     * @throws SessionNotExistException No session exists.
     */
    synchronized public com.zeroc.Glacier2.SessionPrx session()
        throws SessionNotExistException
    {
        if(_session == null)
        {
            throw new SessionNotExistException();
        }

        return _session;
    }

    /**
     * Returns true if there is an active session, otherwise returns false.
     * @return <code>true</code>if session exists or false if no session exists.
     */
    synchronized public boolean isConnected()
    {
        return _connected;
    }

    /**
     * Creates an object adapter for callback objects.
     * @return The object adapter.
     * @throws SessionNotExistException No session exists.
     */
    synchronized public com.zeroc.Ice.ObjectAdapter objectAdapter()
        throws SessionNotExistException
    {
        return internalObjectAdapter();
    }

    //
    // Only call this method when the calling thread owns the lock
    //
    private com.zeroc.Ice.ObjectAdapter internalObjectAdapter()
        throws SessionNotExistException
    {
        if(_router == null)
        {
            throw new SessionNotExistException();
        }
        if(!_useCallbacks)
        {
            throw new com.zeroc.Ice.InitializationException(
                "Object adapter not available, call SessionFactoryHelper.setUseCallbacks(true)");
        }
        return _adapter;
    }

    private interface ConnectStrategy
    {
        com.zeroc.Glacier2.SessionPrx connect(com.zeroc.Glacier2.RouterPrx router)
            throws CannotCreateSessionException, PermissionDeniedException;
    }

    /**
     * Connects to the Glacier2 router using the associated SSL credentials.
     *
     * Once the connection is established, {@link SessionCallback#connected} is called on the callback object;
     * upon failure, {@link SessionCallback#connectFailed} is called with the exception.
     *
     * @param context The request context to use when creating the session.
     */
    synchronized protected void connect(final java.util.Map<String, String> context)
    {
        connectImpl(new ConnectStrategy()
                            {
                                @Override
                                public SessionPrx connect(RouterPrx router)
                                    throws CannotCreateSessionException, PermissionDeniedException
                                {
                                    return router.createSessionFromSecureConnection(context);
                                }
                            });
    }

    /**
     * Connects a Glacier2 session using user name and password credentials.
     *
     * Once the connection is established, {@link SessionCallback#connected} is called on the callback object;
     * upon failure {@link SessionCallback#connectFailed} is called with the exception.
     *
     * @param username The user name.
     * @param password The password.
     * @param context The request context to use when creating the session.
     */
    synchronized protected void connect(final String username, final String password,
                                        final java.util.Map<String, String> context)
    {
        connectImpl(new ConnectStrategy()
                            {
                                @Override
                                public SessionPrx connect(RouterPrx router)
                                    throws CannotCreateSessionException, PermissionDeniedException
                                {
                                    return router.createSession(username, password, context);
                                }
                            });
    }

    private void connected(RouterPrx router, SessionPrx session)
    {
        //
        // Remote invocation should be done without acquiring a mutex lock.
        //
        assert(router != null);
        com.zeroc.Ice.Connection conn = router.ice_getCachedConnection();
        String category = router.getCategoryForClient();
        int acmTimeout = 0;
        try
        {
            acmTimeout = router.getACMTimeout();
        }
        catch(com.zeroc.Ice.OperationNotExistException ex)
        {
        }

        if(acmTimeout <= 0)
        {
            acmTimeout = (int)router.getSessionTimeout();
        }

        //
        // We create the callback object adapter here because createObjectAdapter internally
        // makes synchronous RPCs to the router. We can't create the OA on-demand when the
        // client calls objectAdapter() or addWithUUID() because they can be called from the
        // GUI thread.
        //
        if(_useCallbacks)
        {
            assert(_adapter == null);
            _adapter = _communicator.createObjectAdapterWithRouter("", router);
            _adapter.activate();
        }

        synchronized(this)
        {
            _router = router;

            if(_destroy)
            {
                //
                // Run destroyInternal in a thread because it makes remote invocations.
                //
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        destroyInternal();
                    }
                }).start();
                return;
            }

            //
            // Cache the category.
            //
            _category = category;

            //
            // Assign the session after _destroy is checked.
            //
            _session = session;
            _connected = true;

            if(acmTimeout > 0)
            {
                com.zeroc.Ice.Connection connection = _router.ice_getCachedConnection();
                assert(connection != null);
                connection.setACM(java.util.OptionalInt.of(acmTimeout),
                                  null,
                                  java.util.Optional.of(ACMHeartbeat.HeartbeatAlways));
                connection.setCloseCallback(con -> destroy());
            }

            _shutdownHook = new Thread("Shutdown hook")
            {
                @Override
                public void run()
                {
                    SessionHelper.this.destroy();
                }
            };

            try
            {
                Runtime.getRuntime().addShutdownHook(_shutdownHook);
            }
            catch(IllegalStateException e)
            {
                //
                // Shutdown in progress, ignored
                //
            }
            catch(SecurityException ex)
            {
                //
                // Ignore. Unsigned applets cannot registered shutdown hooks.
                //
            }
        }

        dispatchCallback(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        _callback.connected(SessionHelper.this);
                    }
                    catch(SessionNotExistException ex)
                    {
                        SessionHelper.this.destroy();
                    }
                }
            }, conn);
    }

    private void destroyInternal()
    {
        assert _destroy;
        com.zeroc.Glacier2.RouterPrx router = null;
        com.zeroc.Ice.Communicator communicator = null;
        synchronized(this)
        {
            if(_router == null)
            {
                return;
            }

            router = _router;
            _router = null;

            communicator = _communicator;
        }

        assert communicator != null;

        try
        {
            router.destroySession();
        }
        catch(com.zeroc.Ice.ConnectionLostException e)
        {
            //
            // Expected if another thread invoked on an object from the session concurrently.
            //
        }
        catch(SessionNotExistException e)
        {
            //
            // This can also occur.
            //
        }
        catch(Throwable e)
        {
            //
            // Not expected.
            //
            communicator.getLogger().warning("SessionHelper: unexpected exception when destroying the session:\n" + e);
        }

        communicator.destroy();

        //
        // Notify the callback that the session is gone.
        //
        dispatchCallback(new Runnable()
        {
            @Override
            public void run()
            {
                _callback.disconnected(SessionHelper.this);
            }
        }, null);
    }

    private void destroyCommunicator()
    {
        com.zeroc.Ice.Communicator communicator = null;
        synchronized(this)
        {
            communicator = _communicator;
        }

        if(communicator != null)
        {
            _communicator.destroy();
        }
    }

    private void connectImpl(final ConnectStrategy factory)
    {
        assert !_destroy;

        try
        {
            _communicator = com.zeroc.Ice.Util.initialize(_initData);
        }
        catch(final com.zeroc.Ice.LocalException ex)
        {
            _destroy = true;
            new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        dispatchCallback(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                _callback.connectFailed(SessionHelper.this, ex);
                            }
                        }, null);
                    }
                }).start();
            return;
        }

        final com.zeroc.Ice.RouterFinderPrx finder =
            com.zeroc.Ice.RouterFinderPrx.uncheckedCast(_communicator.stringToProxy(_finderStr));
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                if(_communicator.getDefaultRouter() == null)
                {
                    try
                    {
                        _communicator.setDefaultRouter(finder.getRouter());
                    }
                    catch(final com.zeroc.Ice.CommunicatorDestroyedException ex)
                    {
                        dispatchCallback(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                _callback.connectFailed(SessionHelper.this, ex);
                            }
                        }, null);
                        return;
                    }
                    catch(Exception ex)
                    {
                        //
                        // In case of error getting router identity from RouterFinder use
                        // default identity.
                        //
                        com.zeroc.Ice.Identity ident = new com.zeroc.Ice.Identity("router", "Glacier2");
                        _communicator.setDefaultRouter(
                            com.zeroc.Ice.RouterPrx.uncheckedCast(finder.ice_identity(ident)));
                    }
                }

                try
                {
                    dispatchCallbackAndWait(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            _callback.createdCommunicator(SessionHelper.this);
                        }
                    });

                    com.zeroc.Glacier2.RouterPrx routerPrx = com.zeroc.Glacier2.RouterPrx.uncheckedCast(
                        _communicator.getDefaultRouter());
                    com.zeroc.Glacier2.SessionPrx session = factory.connect(routerPrx);
                    connected(routerPrx, session);
                }
                catch(final Exception ex)
                {
                    _communicator.destroy();

                    dispatchCallback(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            _callback.connectFailed(SessionHelper.this, ex);
                        }
                    }, null);
                }
            }
        }).start();
    }

    private void dispatchCallback(Runnable runnable, com.zeroc.Ice.Connection conn)
    {
        if(_initData.dispatcher != null)
        {
            _initData.dispatcher.dispatch(runnable, conn);
        }
        else
        {
            runnable.run();
        }
    }

    private void dispatchCallbackAndWait(final Runnable runnable)
    {
        if(_initData.dispatcher != null)
        {
            final java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(0);
            _initData.dispatcher.dispatch(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        runnable.run();
                        sem.release();
                    }
                }, null);
            sem.acquireUninterruptibly();
        }
        else
        {
            runnable.run();
        }
    }

    private final InitializationData _initData;
    private com.zeroc.Ice.Communicator _communicator;
    private com.zeroc.Ice.ObjectAdapter _adapter;
    private com.zeroc.Glacier2.RouterPrx _router;
    private com.zeroc.Glacier2.SessionPrx _session;
    private String _category;
    private String _finderStr;
    private boolean _useCallbacks;

    private final SessionCallback _callback;
    private boolean _destroy = false;
    private boolean _connected = false;
    private Thread _shutdownHook;
}
