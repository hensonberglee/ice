// **********************************************************************
//
// Copyright (c) 2002
// MutableRealms, Inc.
// Huntsville, AL, USA
//
// All Rights Reserved
//
// **********************************************************************

#ifndef ICE_OUTGOING_H
#define ICE_OUTGOING_H

#include <Ice/EmitterF.h>
#include <Ice/Buffer.h>

namespace _Ice
{

class ICE_API Outgoing
{
public:

    Outgoing(const Emitter&, const char*);
    ~Outgoing();

    void invoke();

    Buffer* obuf() { return &obuf_; }
    Buffer* ibuf() { return &ibuf_; }

private:

    Outgoing(const Outgoing&);
    void operator=(const Outgoing&);

    const Emitter& emitter_;
    const char* operation_;
    Buffer obuf_;
    Buffer ibuf_;
};

}

#endif
