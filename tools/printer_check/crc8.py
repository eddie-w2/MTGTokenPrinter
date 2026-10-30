class crc8:
    def __init__(self):
        self.c = 0
    def update(self, data):
        for b in data:
            c = self.c ^ b
            for _ in range(8):
                c = ((c << 1) ^ 0x07) & 0xFF if c & 0x80 else (c << 1) & 0xFF
            self.c = c
    def digest(self):
        return bytes([self.c])
