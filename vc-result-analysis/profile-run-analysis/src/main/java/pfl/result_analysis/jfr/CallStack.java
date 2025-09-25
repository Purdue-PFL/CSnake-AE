package pfl.result_analysis.jfr;

import java.util.Arrays;

public class CallStack {
   public String[] names = new String[16];
   public byte[] types = new byte[16];
   int size;

   public CallStack() {
   }

   public void push(String name, byte type) {
      if (this.size >= this.names.length) {
         this.names = (String[])Arrays.copyOf(this.names, this.size * 2);
         this.types = Arrays.copyOf(this.types, this.size * 2);
      }

      this.names[this.size] = name;
      this.types[this.size] = type;
      ++this.size;
   }

   public void pop() {
      --this.size;
   }

   public void clear() {
      this.size = 0;
   }
}
