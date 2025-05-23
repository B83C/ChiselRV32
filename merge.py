with open('dhrystone_0.data', 'r') as f0, \
     open('dhrystone_1.data', 'r') as f1, \
     open('dhrystone_2.data', 'r') as f2, \
     open('dhrystone_3.data', 'r') as f3, \
     open('dhrystone.data', 'w') as fout:
    for b0, b1, b2, b3 in zip(f0, f1, f2, f3):
        word = b3.strip() + b2.strip() + b1.strip() + b0.strip()
        fout.write(word + '\n')
