AION_HOME=../..

SONAME=libblake2b.dylib
TARGET=${AION_HOME}/native/linux/blake2b/$(SONAME)

#CC=gcc
CC=clang
CFLAGS=-O3 -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/darwin -I/usr/include -shared -fpic  -Wall -Wextra -msse4.2 -mpclmul -maes 
LFLAGS=-Wl,-install_name $(SO_NAME) -shared -lc

all:
	$(CC) $(CFLAGS) \
blake2b.c \
org_aion_crypto_hash_Blake2bNative.c \
$(LFLAGS) -o ${TARGET}

clean:
	rm -rf ${TARGET}
