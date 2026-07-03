pragma circom 2.2.2;
// Original circuits from https://github.com/tornadocash/tornado-nova
// Adapted and modified by Nethermind

include "./poseidon2/poseidon2_hash.circom";

// Since we don't use signatures, the keypair can be based on a simple hash.
// Checks if the public key is the hash of the private key.
template Keypair() {
    /** PRIVATE INPUTS **/
    signal input privateKey;
    /** PUBLIC OUTPUTS **/
    signal output publicKey;

    component hasher = Poseidon2(2);
    hasher.inputs[0] <== privateKey;
    hasher.inputs[1] <== 0; // Padding to widen the permutation state to t=3, providing better security margin than t=2 (Poseidon2(1))
    hasher.domainSeparation <== 0x03; // Domain separation for Keypair
    publicKey <== hasher.out;
}

// Derived nullifier key (Zcash-style nk split): the holder of `nk` can compute
// nullifiers for notes they own (see the Signature/nullifier flow below), but
// cannot spend — spend authority remains with `privateKey` via the
// Keypair/commitment ownership path. Exact mirror of Keypair with a distinct
// domain separator.
template NullifierKey() {
    /** PRIVATE INPUTS **/
    signal input privateKey;
    /** PUBLIC OUTPUTS **/
    signal output nk;

    component hasher = Poseidon2(2);
    hasher.inputs[0] <== privateKey;
    hasher.inputs[1] <== 0; // Padding to widen the permutation state to t=3, providing better security margin than t=2 (Poseidon2(1))
    hasher.domainSeparation <== 5; // Domain separation for NullifierKey
    nk <== hasher.out;
}

// Defines a signature as hash(privateKey, commitment, merklePath)
template Signature() {
    signal input privateKey;
    signal input commitment;
    signal input merklePath;
    signal output out;

    component hasher = Poseidon2(3);
    hasher.inputs[0] <== privateKey;
    hasher.inputs[1] <== commitment;
    hasher.inputs[2] <== merklePath; 
    hasher.domainSeparation <== 0x04; // Domain separation for Signature
    out <== hasher.out;
}