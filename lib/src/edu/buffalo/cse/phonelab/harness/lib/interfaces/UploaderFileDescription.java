package edu.buffalo.cse.phonelab.harness.lib.interfaces;

public class UploaderFileDescription {
	public String src;
	public String dest;
	public long len;
	public UploaderClient uploader;
	
	public UploaderFileDescription(String src, String dest, long len) {
		super();
		this.src = src;
		this.dest = dest;
		this.len = len;
	}

	@Override
	public String toString() {
		return "UploaderFileDescription [src=" + src + ", dest=" + dest
				+ ", len=" + len + "]";
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((dest == null) ? 0 : dest.hashCode());
		result = prime * result + (int) (len ^ (len >>> 32));
		result = prime * result + ((src == null) ? 0 : src.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		UploaderFileDescription other = (UploaderFileDescription) obj;
		if (dest == null) {
			if (other.dest != null)
				return false;
		} else if (!dest.equals(other.dest))
			return false;
		if (len != other.len)
			return false;
		if (src == null) {
			if (other.src != null)
				return false;
		} else if (!src.equals(other.src))
			return false;
		return true;
	}
}
