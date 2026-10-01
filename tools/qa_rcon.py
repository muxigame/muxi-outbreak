"""RCON helper bound to this project's isolated test instance only."""
from __future__ import annotations
import json
from pathlib import Path
import socket
import struct
import sys

ROOT=Path(__file__).resolve().parents[1]


class Rcon:
    def __init__(self):
        config=json.loads((ROOT/'build/qa-server/run.json').read_text(encoding='utf-8'))
        if config['host']!='127.0.0.1' or config['rconPort']!=25684:
            raise ValueError('QA guard: refusing any non-fixture endpoint')
        self.sock=socket.create_connection(('127.0.0.1',25684),timeout=30)
        self.send(1,3,config['rconPassword'])
        ident,_,_=self.receive()
        if ident==-1:raise ValueError('QA RCON auth failed')

    def exact(self,count):
        data=b''
        while len(data)<count:
            part=self.sock.recv(count-len(data))
            if not part:raise ConnectionError('RCON disconnected')
            data+=part
        return data

    def send(self,ident,kind,text):
        payload=struct.pack('<ii',ident,kind)+text.encode('utf-8')+b'\x00\x00'
        self.sock.sendall(struct.pack('<i',len(payload))+payload)

    def receive(self):
        length=struct.unpack('<i',self.exact(4))[0]
        if not 10<=length<=4_000_000:raise ValueError('invalid RCON packet')
        data=self.exact(length)
        return *struct.unpack_from('<ii',data),data[8:-2].decode('utf-8')

    def command(self,text):
        self.send(2,2,text)
        _,_,value=self.receive()
        return value

    def close(self):self.sock.close()


if __name__=='__main__':
    r=Rcon()
    try:print(r.command(' '.join(sys.argv[1:])))
    finally:r.close()
