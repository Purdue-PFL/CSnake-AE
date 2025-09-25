from google.protobuf import wrappers_pb2 as _wrappers_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ClientID(_message.Message):
    __slots__ = ("ipAddr", "uuid")
    IPADDR_FIELD_NUMBER: _ClassVar[int]
    UUID_FIELD_NUMBER: _ClassVar[int]
    ipAddr: str
    uuid: str
    def __init__(self, ipAddr: _Optional[str] = ..., uuid: _Optional[str] = ...) -> None: ...

class Task(_message.Message):
    __slots__ = ("expKey", "btmStr", "unittestFunc", "injectionTimeMs", "unittestTimeoutMs")
    EXPKEY_FIELD_NUMBER: _ClassVar[int]
    BTMSTR_FIELD_NUMBER: _ClassVar[int]
    UNITTESTFUNC_FIELD_NUMBER: _ClassVar[int]
    INJECTIONTIMEMS_FIELD_NUMBER: _ClassVar[int]
    UNITTESTTIMEOUTMS_FIELD_NUMBER: _ClassVar[int]
    expKey: str
    btmStr: str
    unittestFunc: str
    injectionTimeMs: int
    unittestTimeoutMs: int
    def __init__(self, expKey: _Optional[str] = ..., btmStr: _Optional[str] = ..., unittestFunc: _Optional[str] = ..., injectionTimeMs: _Optional[int] = ..., unittestTimeoutMs: _Optional[int] = ...) -> None: ...

class TaskResult(_message.Message):
    __slots__ = ("clientID", "expKey", "testOutput")
    CLIENTID_FIELD_NUMBER: _ClassVar[int]
    EXPKEY_FIELD_NUMBER: _ClassVar[int]
    TESTOUTPUT_FIELD_NUMBER: _ClassVar[int]
    clientID: ClientID
    expKey: str
    testOutput: bytes
    def __init__(self, clientID: _Optional[_Union[ClientID, _Mapping]] = ..., expKey: _Optional[str] = ..., testOutput: _Optional[bytes] = ...) -> None: ...
