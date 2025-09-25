import zstandard
import io

class ZstdReader:
    def __init__(self, filename):
        self.filename = filename

    def __enter__(self):
        self.f = open(self.filename, 'rb')
        dctx = zstandard.ZstdDecompressor()
        reader = dctx.stream_reader(self.f)
        return io.TextIOWrapper(reader, encoding='utf-8')

    def __exit__(self, *a):
        self.f.close()
        return False
    
class ZstdWriter:
    def __init__(self, filename):
        self.filename = filename

    def __enter__(self):
        self.f = open(self.filename, 'wb')
        ctx = zstandard.ZstdCompressor(level=5)
        self.writer = ctx.stream_writer(self.f)
        self.iw = io.TextIOWrapper(self.writer, encoding='utf-8')
        return self.iw

    def __exit__(self, *a):
        self.iw.flush()
        self.writer.flush(zstandard.FLUSH_FRAME)
        self.f.close()
        return False


def openZstd(filename, mode='rb'):
    if 'w' in mode:
        return ZstdWriter(filename)
    return ZstdReader(filename)
