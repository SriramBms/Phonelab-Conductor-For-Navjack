package edu.buffalo.cse.phonelab.harness.lib.interfaces;

import java.io.File;

import org.simpleframework.xml.Element;

public class UploaderFileDescription {
	@Element
	public String src;
	
	@Element
	public String filename;
	
	@Element
	public String packagename;
	
	public long len;
	public UploaderClient uploader;
	
	public UploaderFileDescription(@Element (name = "src") String src,
			@Element (name = "filename") String filename,
			@Element (name = "packagename") String packagename) throws Exception {
		super();
		this.src = src;
		if (this.exists() == false) {
			throw new Exception("file does not exist");
		}
		this.filename = filename;
		this.packagename = packagename;
		this.len = new File(this.src).length();
	}

	@Override
	public String toString() {
		if (this.packagename != null) {
			return "UploaderFileDescription [src=" + src + ", filename=" + filename
					+ ", packagename=" + packagename + ", len=" + len
					+ ", uploader=" + uploader + "]";
		} else {
			return "UploaderFileDescription [src=" + src + ", filename=" + filename
					+ ", len=" + len + ", uploader=" + uploader + "]";
		}
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
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
		if (src == null) {
			if (other.src != null)
				return false;
		} else if (!src.equals(other.src))
			return false;
		return true;
	}
	
	public boolean exists() {
        File file = new File(this.src);
        if ((file.exists() == false) || (file.isFile() == false) || (file.canRead() == false)) {
            return false;
        } else {
        	return true;
        }
	}
}
